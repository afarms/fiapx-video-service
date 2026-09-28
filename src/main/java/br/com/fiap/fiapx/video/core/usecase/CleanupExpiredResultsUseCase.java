package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.gateway.DownloadGateway;
import br.com.fiap.fiapx.video.core.gateway.ResultCleanupStorageGateway;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Each claim commits before storage I/O; confirmation and retry are fenced by its token. */
public class CleanupExpiredResultsUseCase {
    private final DownloadGateway gateway;
    private final ResultCleanupStorageGateway storage;
    private final Duration lease, retry;
    private final int batch;
    private final LongSupplier nanoTime;
    private final AtomicBoolean running = new AtomicBoolean();
    // Storage must enforce a 15s total call timeout. Keep another 5s for scheduling/confirmation.
    private static final long IO_BUDGET = Duration.ofSeconds(20).toNanos();

    public CleanupExpiredResultsUseCase(DownloadGateway gateway, ResultCleanupStorageGateway storage,
            Duration lease, Duration retry, int batch, LongSupplier nanoTime) {
        if (lease == null || lease.getNano() != 0 || lease.getSeconds() < 45 || lease.getSeconds() > 86400
                || retry == null || retry.getNano() != 0 || retry.getSeconds() < 1 || retry.getSeconds() > 86400
                || batch < 1 || batch > 100) throw new IllegalArgumentException("Invalid result cleanup limits");
        this.gateway = gateway; this.storage = storage; this.lease = lease; this.retry = retry;
        this.batch = batch; this.nanoTime = nanoTime;
    }

    public int execute() {
        if (!running.compareAndSet(false, true)) return 0;
        try {
            int completed = 0;
            for (int i = 0; i < batch; i++) {
                long started = nanoTime.getAsLong();
                // Claim only what this reader can process immediately, never a waiting batch of leases.
                var claims = gateway.claimExpired(1, lease);
                if (claims.isEmpty()) break;
                var claim = claims.getFirst();
                var video = claim.artifact().videoId();
                if (nanoTime.getAsLong() - started >= lease.toNanos() - IO_BUDGET) {
                    gateway.retryCleanup(video, claim.token(), retry);
                    continue;
                }
                try {
                    storage.delete(claim.artifact());
                    if (gateway.deleted(video, claim.token())) completed++;
                } catch (RuntimeException failure) {
                    // A failed acknowledgement also retries the idempotent delete. If SQL is unavailable,
                    // propagate to the scheduler; the committed claim remains recoverable by expiration.
                    gateway.retryCleanup(video, claim.token(), retry);
                }
            }
            return completed;
        } finally { running.set(false); }
    }
}
