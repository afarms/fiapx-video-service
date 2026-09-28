package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.exception.UploadException;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;

/** Bounds requests before multipart parsing and locks each staging file until the request ends. */
public class UploadFiles {
    private final Path directory;
    private final Semaphore slots;
    private final int concurrency;
    private final long diskReserve;
    private final Clock clock;

    public UploadFiles(Path directory, int concurrency, long diskReserve, Clock clock) throws IOException {
        if (concurrency < 1 || diskReserve < 202_000_000L) throw new IllegalArgumentException("Invalid upload capacity");
        this.directory = directory.toAbsolutePath().normalize();
        this.slots = new Semaphore(concurrency);
        this.concurrency = concurrency;
        this.diskReserve = diskReserve;
        this.clock = clock;
        Files.createDirectories(this.directory);
        cleanStaleFiles();
    }

    public synchronized boolean acquire() {
        if (!slots.tryAcquire()) return false;
        try {
            long otherReservations = 202_000_000L * (concurrency - slots.availablePermits() - 1);
            if (Files.getFileStore(directory).getUsableSpace() - otherReservations >= diskReserve) return true;
        } catch (IOException unavailable) { /* refuse admission without disk information */ }
        slots.release();
        return false;
    }

    public void release() { slots.release(); }

    public Path directory() { return directory; }

    public Staged stage(InputStream input) {
        Path file = null;
        FileChannel channel = null;
        FileLock lock = null;
        try {
            file = Files.createTempFile(directory, "upload-", ".tmp");
            // Lock a sidecar: Windows locks on the data file would prevent the SDK from reading it.
            channel = FileChannel.open(lockPath(file), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.lock();
            var digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] buffer = new byte[64 * 1024];
            int count;
            try (var output = Files.newOutputStream(file)) {
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > Video.MAX_SIZE_BYTES) throw new UploadException(UploadException.Reason.TOO_LARGE);
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (size == 0) throw new UploadException(UploadException.Reason.INVALID);
            return new Staged(file, size, HexFormat.of().formatHex(digest.digest()), channel, lock);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException failure) {
            closeAndDelete(file, channel, lock);
            if (failure instanceof UploadException known) throw known;
            throw new UploadException(UploadException.Reason.UNAVAILABLE, failure);
        }
    }

    public void cleanStaleFiles() throws IOException {
        try (var paths = Files.newDirectoryStream(directory, "upload-*.tmp")) {
            for (var file : paths) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.getLastModifiedTime(file).toInstant().isAfter(clock.instant().minusSeconds(900))) continue;
                try (var channel = FileChannel.open(lockPath(file), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    try (var lock = channel.tryLock()) {
                        if (lock == null) continue;
                    }
                } catch (OverlappingFileLockException active) { continue; }
                // Files have unguessable unique names; no live request reopens an abandoned file.
                Files.deleteIfExists(file);
                Files.deleteIfExists(lockPath(file));
            }
        }
    }

    private static void closeAndDelete(Path file, FileChannel channel, FileLock lock) {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
        try { if (channel != null) channel.close(); } catch (IOException ignored) { }
        try {
            if (file != null) {
                Files.deleteIfExists(file);
                Files.deleteIfExists(lockPath(file));
            }
        } catch (IOException ignored) { /* startup cleanup retries */ }
    }

    private static Path lockPath(Path file) { return file.resolveSibling(file.getFileName() + ".lock"); }

    public record Staged(Path path, long size, String sha256, FileChannel channel, FileLock lock) implements AutoCloseable {
        public void close() { closeAndDelete(path, channel, lock); }
    }
}
