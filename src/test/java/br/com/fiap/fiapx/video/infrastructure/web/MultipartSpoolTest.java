package br.com.fiap.fiapx.video.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MultipartSpoolTest {
    @TempDir Path root;

    @Test void recoversAbandonedParserFilesWithoutTouchingAnotherRunningInstance() throws Exception {
        var abandoned = Files.createDirectory(root.resolve("multipart-abandoned"));
        Files.writeString(abandoned.resolve("upload_old.tmp"), "interrupted upload");
        Path firstDirectory;
        try (var first = new MultipartSpool(root)) {
            assertFalse(Files.exists(abandoned));
            firstDirectory = first.directory();
            var active = Files.writeString(firstDirectory.resolve("upload_active.tmp"), "active");
            try (var second = new MultipartSpool(root)) {
                assertNotEquals(firstDirectory, second.directory());
                assertEquals("active", Files.readString(active));
            }
        }
        assertFalse(Files.exists(firstDirectory));
    }
}
