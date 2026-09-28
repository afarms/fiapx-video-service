package br.com.fiap.fiapx.video.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Permanent client intention; only the execution lease may expire. */
public record UploadIntent(Video video, UUID idempotencyKey, String sha256, UUID attemptId,
                           Instant leaseUntil, Instant acceptedAt) {
    public UploadIntent {
        Objects.requireNonNull(video, "video is required");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        Objects.requireNonNull(attemptId, "attemptId is required");
        Objects.requireNonNull(leaseUntil, "leaseUntil is required");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 lowercase hexadecimal characters");
        }
        if (leaseUntil.isBefore(video.createdAt())) {
            throw new IllegalArgumentException("leaseUntil precedes creation");
        }
        if ((video.state() != VideoStatus.UPLOADING) != (acceptedAt != null)) {
            throw new IllegalArgumentException("Accepted intentions require their original acceptance time");
        }
        if (acceptedAt != null && acceptedAt.isBefore(video.createdAt())) {
            throw new IllegalArgumentException("acceptedAt precedes creation");
        }
        if (!video.originalObjectKey().equals(objectKey(video.ownerId(), video.id(), attemptId))) {
            throw new IllegalArgumentException("Object key must identify this owner, video and attempt");
        }
    }

    public static String objectKey(UUID ownerId, UUID videoId, UUID attemptId) {
        return "originals/" + Objects.requireNonNull(ownerId) + "/"
                + Objects.requireNonNull(videoId) + "/" + Objects.requireNonNull(attemptId);
    }

    public boolean matches(String originalName, long sizeBytes, String hash) {
        return video.originalName().equals(originalName) && video.sizeBytes() == sizeBytes && sha256.equals(hash);
    }

    public boolean activeAt(Instant now) {
        Objects.requireNonNull(now, "now is required");
        return video.state() == VideoStatus.UPLOADING && leaseUntil.isAfter(now);
    }

    /** Persistence must also compare the token and lease atomically using database time. */
    public UploadIntent accept(UUID token, Instant now) {
        if (!attemptId.equals(token) || !activeAt(now)) {
            throw new IllegalStateException("Upload attempt no longer owns the reservation");
        }
        return new UploadIntent(video.queued(), idempotencyKey, sha256, attemptId, leaseUntil, now);
    }
}
