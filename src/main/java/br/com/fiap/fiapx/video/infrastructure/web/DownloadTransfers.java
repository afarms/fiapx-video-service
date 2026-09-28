package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.exception.DownloadException;
import br.com.fiap.fiapx.video.core.gateway.DownloadGateway;
import br.com.fiap.fiapx.video.infrastructure.storage.DownloadStorage;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded readers, nonblocking servlet writes and an independent lease watchdog. */
public class DownloadTransfers implements AutoCloseable {
    private final DownloadGateway gateway;
    private final DownloadStorage storage;
    private final Duration lease, maximum, heartbeat;
    private final Semaphore slots;
    private final ExecutorService readers, renewals;
    private final ScheduledExecutorService watchdog;
    private final Set<Transfer> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private static final long SAFETY = TimeUnit.SECONDS.toNanos(5);

    public DownloadTransfers(DownloadGateway gateway, DownloadStorage storage, int concurrency,
                             Duration lease, Duration maximum, Duration heartbeat) {
        if (concurrency < 1 || concurrency > 32 || lease.getSeconds() < 30 || lease.getSeconds() > 86400
                || maximum.compareTo(lease) < 0 || maximum.getSeconds() > 86400
                || heartbeat.getSeconds() < 1 || heartbeat.multipliedBy(3).compareTo(lease) > 0) {
            throw new IllegalArgumentException("Invalid download limits");
        }
        this.gateway = gateway; this.storage = storage; this.lease = lease;
        this.maximum = maximum; this.heartbeat = heartbeat;
        slots = new Semaphore(concurrency);
        readers = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("download-read-", 0).factory());
        renewals = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency), Thread.ofPlatform().name("download-renew-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("download-watch-", 0).factory());
        watchdog.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** Reserve capacity before DB/S3 admission. No unbounded pending transfer queue. */
    public void acquire() {
        if (closed || !slots.tryAcquire()) throw new DownloadException(DownloadException.Reason.UNAVAILABLE);
    }
    public void cancelAdmission() { slots.release(); }

    public void start(HttpServletRequest request, HttpServletResponse response, DownloadGateway.Lease reservation,
                      long admissionStarted) {
        Transfer transfer = new Transfer(request.startAsync(), response, reservation, admissionStarted,
                "HTTP/1.1".equals(request.getProtocol()));
        active.add(transfer);
        try {
            if (closed) throw new RejectedExecutionException();
            transfer.async.setTimeout(maximum.toMillis());
            transfer.async.addListener(transfer);
            readers.execute(transfer);
        } catch (RuntimeException failure) {
            transfer.stop();
            transfer.dispose();
        }
    }

    private void tick() {
        long now = System.nanoTime();
        for (Transfer transfer : active) {
            if (!transfer.stopped && now - transfer.validUntil >= 0) transfer.stop();
            else if (!transfer.stopped && now - transfer.nextRenewal >= 0
                    && transfer.renewing.compareAndSet(false, true)) {
                try { renewals.execute(transfer::renew); }
                catch (RejectedExecutionException failure) { transfer.stop(); }
            }
        }
    }

    public void close() {
        closed = true;
        active.forEach(Transfer::stop);
        watchdog.shutdownNow(); readers.shutdown(); renewals.shutdownNow();
    }

    private final class Transfer implements Runnable, WriteListener, AsyncListener {
        final AsyncContext async;
        final HttpServletResponse response;
        final DownloadGateway.Lease reservation;
        final long deadline;
        final boolean closeConnection;
        final AtomicBoolean renewing = new AtomicBoolean();
        volatile long validUntil, nextRenewal;
        volatile boolean stopped;
        volatile DownloadStorage.Body body;
        ServletOutputStream output;
        boolean succeeded;
        boolean httpCompleted;

        Transfer(AsyncContext async, HttpServletResponse response, DownloadGateway.Lease reservation, long started,
                 boolean closeConnection) {
            this.async = async; this.response = response; this.reservation = reservation;
            this.closeConnection = closeConnection;
            deadline = started + maximum.toNanos() - SAFETY;
            validUntil = started + lease.toNanos() - SAFETY;
            nextRenewal = started + heartbeat.toNanos();
        }

        public void run() {
            try {
                check();
                body = storage.open(reservation.artifact());
                check();
                synchronized (this) {
                    check();
                    response.setStatus(200);
                    response.setContentType("application/zip");
                    response.setContentLengthLong(reservation.artifact().sizeBytes());
                    response.setHeader("Content-Disposition", "attachment; filename=\"frames-" + reservation.artifact().videoId() + ".zip\"");
                    response.setHeader("Cache-Control", "private, no-store");
                    response.setHeader("Accept-Ranges", "none");
                    // A truncated fixed-length HTTP/1.1 body must end at EOF, never await keep-alive timeout.
                    // HTTP/2 uses END_STREAM and must not receive this hop-by-hop header.
                    if (closeConnection) response.setHeader("Connection", "close");
                    output = response.getOutputStream();
                    output.setWriteListener(this);
                }
                byte[] buffer = new byte[64 * 1024];
                long remaining = reservation.artifact().sizeBytes();
                while (remaining > 0) {
                    check();
                    int count = body.input().read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("Incomplete result");
                    synchronized (this) {
                        while (!stopped && !output.isReady()) wait(100);
                        check();
                        output.write(buffer, 0, count);
                    }
                    remaining -= count;
                }
                succeeded = true;
            } catch (Exception failure) {
                stop();
            } finally {
                dispose();
            }
        }

        private void check() throws IOException {
            if (stopped || System.nanoTime() - validUntil >= 0) throw new IOException("Download reservation ended");
        }

        void renew() {
            long started = System.nanoTime();
            try {
                if (stopped) return;
                boolean renewed = gateway.renew(reservation.artifact().videoId(), reservation.token(), lease).isPresent();
                synchronized (this) {
                    if (!renewed || stopped || System.nanoTime() - validUntil >= 0) { stop(); return; }
                    // Call start is conservative relative to the later database clock_timestamp().
                    validUntil = Math.min(deadline, started + lease.toNanos() - SAFETY);
                    nextRenewal = started + heartbeat.toNanos();
                }
            } catch (RuntimeException failure) { stop(); }
            finally { renewing.set(false); }
        }

        synchronized void stop() {
            stopped = true;
            notifyAll();
            // No blocking client write holds this monitor: ServletOutputStream is nonblocking.
            var current = body;
            if (current != null) {
                try { current.abort(); } catch (RuntimeException ignored) { /* SDK socket timeout is the fallback. */ }
            }
        }

        void dispose() {
            stop();
            try {
                completeHttp();
            } finally {
                // Release only after upstream I/O ended. Failure leaves the durable lease to expire.
                try { gateway.release(reservation.artifact().videoId(), reservation.token()); }
                catch (RuntimeException ignored) { /* Expiring reservation recovers database outages. */ }
                active.remove(this);
                slots.release();
            }
        }

        synchronized void completeHttp() {
            if (httpCompleted) return;
            httpCompleted = true;
            try {
                if (!succeeded && !response.isCommitted()) {
                    response.reset();
                    response.setStatus(503);
                    response.setHeader("Cache-Control", "no-store");
                }
                try { async.complete(); } catch (IllegalStateException ignored) { /* Container already completed. */ }
            } catch (IllegalStateException ignored) {
                // A concurrent container error may have completed the response already.
            }
        }

        public synchronized void onWritePossible() { notifyAll(); }
        public void onError(Throwable failure) { stop(); }
        public void onComplete(AsyncEvent event) { stop(); }
        public void onTimeout(AsyncEvent event) { stop(); completeHttp(); }
        public void onError(AsyncEvent event) { stop(); completeHttp(); }
        public void onStartAsync(AsyncEvent event) { }
    }
}
