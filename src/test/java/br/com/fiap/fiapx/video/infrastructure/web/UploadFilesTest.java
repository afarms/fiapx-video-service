package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.exception.UploadException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class UploadFilesTest {
    @TempDir Path directory;
    final Instant now = Instant.parse("2026-09-27T12:00:00Z");
    UploadFiles files() throws Exception { return new UploadFiles(directory, 1, 202_000_000, Clock.fixed(now, ZoneOffset.UTC)); }
    @Test void computesRealSizeAndHashAndReleasesFiles() throws Exception {
        var files = files(); Path staged;
        try (var data = files.stage(new ByteArrayInputStream("abc".getBytes()))) {
            staged = data.path();
            assertEquals("abc", Files.readString(staged));
            assertEquals(3, data.size());
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", data.sha256());
        }
        assertFalse(Files.exists(staged));
        try(var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }
    @Test void boundsConcurrentRequestsAndRefusesUnavailableDisk() throws Exception {
        var files = files();
        assertTrue(files.acquire()); assertFalse(files.acquire()); files.release(); assertTrue(files.acquire()); files.release();
        var noSpace = new UploadFiles(directory, 1, Long.MAX_VALUE, Clock.systemUTC());
        assertFalse(noSpace.acquire()); assertFalse(noSpace.acquire());
        Files.delete(directory); assertFalse(files.acquire());
    }
    @Test void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new UploadFiles(directory, 0, 202_000_000, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new UploadFiles(directory, 1, 1, Clock.systemUTC()));
    }
    @Test void emptyOrBrokenStreamNeverLeavesStagingFiles() throws Exception {
        var files = files();
        assertEquals(UploadException.Reason.INVALID, assertThrows(UploadException.class,
                () -> files.stage(InputStream.nullInputStream())).reason());
        var broken = new InputStream() { public int read() throws IOException { throw new IOException("failed"); } };
        assertEquals(UploadException.Reason.UNAVAILABLE, assertThrows(UploadException.class, () -> files.stage(broken)).reason());
        try(var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }
    InputStream bytes(long total) {
        return new InputStream() {
            long remaining = total;
            public int read() { return remaining-- > 0 ? 0 : -1; }
            public int read(byte[] buffer, int offset, int length) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length); Arrays.fill(buffer, offset, offset + count, (byte) 0);
                remaining -= count; return count;
            }
        };
    }
    @Test void acceptsExactMaximumAndRejectsOneExtraByteWithoutHeapSizedBuffer() throws Exception {
        var files = files();
        try (var data = files.stage(bytes(100_000_000))) { assertEquals(100_000_000, data.size()); }
        assertEquals(UploadException.Reason.TOO_LARGE,
                assertThrows(UploadException.class, () -> files.stage(bytes(100_000_001))).reason());
        try(var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }
    @Test void startupCleanupRemovesAbandonedFilesButPreservesActiveAndRecentOnes() throws Exception {
        var files = files();
        Path abandoned = Files.writeString(directory.resolve("upload-abandoned.tmp"), "old");
        Files.setLastModifiedTime(abandoned, FileTime.from(now.minusSeconds(901)));
        Path recent = Files.writeString(directory.resolve("upload-recent.tmp"), "recent");
        Files.setLastModifiedTime(recent, FileTime.from(now));
        Files.createDirectory(directory.resolve("upload-directory.tmp"));
        try (var active = files.stage(new ByteArrayInputStream(new byte[]{1}))) {
            Files.setLastModifiedTime(active.path(), FileTime.from(now.minusSeconds(901)));
            files.cleanStaleFiles();
            assertFalse(Files.exists(abandoned)); assertTrue(Files.exists(recent)); assertTrue(Files.exists(active.path()));
        }
    }
}
