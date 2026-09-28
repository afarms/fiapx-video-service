package br.com.fiap.fiapx.video.core.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Metadata only. A QUEUED value must be committed together with its outbox event. */
public record Video(UUID id, UUID ownerId, String originalName, String originalObjectKey,
                    long sizeBytes, Instant createdAt, VideoStatus state,
                    Instant completedAt, Instant expiresAt, Instant failedAt, String failureCode) {
    public static final long MAX_SIZE_BYTES = 100_000_000L;
    private static final Set<String> FORMATS = Set.of("mp4", "avi", "mov", "mkv", "wmv", "flv", "webm");

    public Video {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(ownerId, "ownerId is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(state, "state is required");
        requireText(originalName, 255, "originalName");
        requireText(originalObjectKey, 1024, "originalObjectKey");
        if (originalName.contains("/") || originalName.contains("\\")) {
            throw new IllegalArgumentException("originalName must be a filename, not a path");
        }
        int dot = originalName.lastIndexOf('.');
        if (dot <= 0 || !FORMATS.contains(originalName.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported video extension");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("sizeBytes must be between 1 and 100000000");
        }
    }

    public Video(UUID id, UUID ownerId, String originalName, String originalObjectKey,
                 long sizeBytes, Instant createdAt, VideoStatus state) {
        this(id, ownerId, originalName, originalObjectKey, sizeBytes, createdAt, state, null, null, null, null);
    }

    public Video(UUID id, UUID ownerId, String originalName, String originalObjectKey,
                 long sizeBytes, Instant createdAt) {
        this(id, ownerId, originalName, originalObjectKey, sizeBytes, createdAt, VideoStatus.UPLOADING);
    }

    public String status() {
        return state.name();
    }

    public Video queued() {
        return new Video(id, ownerId, originalName, originalObjectKey, sizeBytes, createdAt, VideoStatus.QUEUED);
    }

    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must be nonblank, within its limit and without NUL");
        }
    }
}
