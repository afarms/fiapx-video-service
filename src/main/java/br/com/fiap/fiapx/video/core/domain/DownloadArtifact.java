package br.com.fiap.fiapx.video.core.domain;

import java.util.Objects;
import java.util.UUID;

/** Private immutable reference; never serialize this record in public video responses. */
public record DownloadArtifact(UUID videoId, UUID ownerId, String bucket, String objectKey, long sizeBytes, String sha256) {
    public DownloadArtifact {
        Objects.requireNonNull(videoId); Objects.requireNonNull(ownerId);
        String prefix = "results/" + ownerId + "/" + videoId + "/";
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || objectKey == null || !objectKey.startsWith(prefix)
                || !objectKey.substring(prefix.length()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/frames\\.zip")
                || sizeBytes <= 0 || sizeBytes > 1_073_741_824L || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid private download reference");
    }
}
