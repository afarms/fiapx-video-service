package br.com.fiap.fiapx.video.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class VideoTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CREATED = Instant.parse("2026-09-22T12:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "avi", "mov", "mkv", "wmv", "flv", "webm", "MP4", "WeBm"})
    void acceptsSupportedExtensionsWithoutClaimingMediaValidation(String extension) {
        Video video = video("sample." + extension, "owners/key", 1);
        assertAll(() -> assertEquals(ID, video.id()), () -> assertEquals(OWNER, video.ownerId()),
                () -> assertEquals(CREATED, video.createdAt()), () -> assertEquals("UPLOADING", video.status()),
                () -> assertEquals(1, video.sizeBytes()),
                () -> assertEquals("sample." + extension, video.originalName()),
                () -> assertEquals("owners/key", video.originalObjectKey()));
    }

    @Test
    void acceptsExactLimits() {
        Video video = video("a".repeat(251) + ".mp4", "k".repeat(1024), Video.MAX_SIZE_BYTES);
        assertEquals(100_000_000, video.sizeBytes());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 100_000_001L, Long.MAX_VALUE})
    void rejectsInvalidSize(long size) {
        assertThrows(IllegalArgumentException.class, () -> video("sample.mp4", "key", size));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "sample", ".mp4", "sample.", "sample.exe", "sample.mp4.exe",
            "../sample.mp4", "folder\\sample.mp4", "bad\0.mp4"})
    void rejectsInvalidNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> video(name, "key", 1));
    }

    @Test
    void rejectsNameAboveLimit() {
        assertThrows(IllegalArgumentException.class, () -> video("a".repeat(252) + ".mp4", "key", 1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n", "bad\0key"})
    void rejectsInvalidObjectKey(String key) {
        assertThrows(IllegalArgumentException.class, () -> video("sample.mp4", key, 1));
    }

    @Test
    void rejectsObjectKeyAboveLimit() {
        assertThrows(IllegalArgumentException.class, () -> video("sample.mp4", "k".repeat(1025), 1));
    }

    @Test
    void requiresIdentifiersAndCreationTime() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new Video(null, OWNER, "sample.mp4", "key", 1, CREATED)),
                () -> assertThrows(NullPointerException.class,
                        () -> new Video(ID, null, "sample.mp4", "key", 1, CREATED)),
                () -> assertThrows(NullPointerException.class,
                        () -> new Video(ID, OWNER, "sample.mp4", "key", 1, null)));
    }

    private Video video(String name, String key, long size) {
        return new Video(ID, OWNER, name, key, size, CREATED);
    }
}
