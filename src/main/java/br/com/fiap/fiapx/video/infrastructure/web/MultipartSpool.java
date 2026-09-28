package br.com.fiap.fiapx.video.infrastructure.web;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;

/** One parser directory per running application; a process lock protects live requests. */
public final class MultipartSpool implements AutoCloseable {
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

    public MultipartSpool(Path root) throws IOException {
        Files.createDirectories(root);
        try (var entries = Files.newDirectoryStream(root, "multipart-*")) {
            for (Path abandoned : entries) {
                if (!Files.isDirectory(abandoned, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var candidate = FileChannel.open(lockPath(abandoned), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    try (var ownership = candidate.tryLock()) {
                        if (ownership != null) removeFiles(abandoned);
                    }
                } catch (OverlappingFileLockException active) { continue; }
                // Keep the small lock file: removing it could split ownership between processes.
            }
        }
        directory = root.resolve("multipart-" + UUID.randomUUID());
        channel = FileChannel.open(lockPath(directory), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        lock = channel.lock();
        try { Files.createDirectory(directory); }
        catch (IOException failure) { lock.release(); channel.close(); throw failure; }
    }

    public Path directory() { return directory; }

    private static Path lockPath(Path directory) {
        return directory.resolveSibling(directory.getFileName() + ".lock");
    }

    private static void removeFiles(Path directory) throws IOException {
        // The servlet parser creates flat files. Never recursively follow directories or links.
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path file : entries) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(directory);
    }

    @Override public void close() throws IOException {
        try { removeFiles(directory); }
        finally { lock.release(); channel.close(); }
    }
}
