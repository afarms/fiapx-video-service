package br.com.fiap.fiapx.video.core.gateway;

import br.com.fiap.fiapx.video.core.domain.UploadIntent;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/** Transaction boundaries belong to the adapter; no transaction spans object storage IO. */
public interface UploadGateway {
    Instant now();
    Optional<UploadIntent> findByOwnerAndKey(UUID ownerId, UUID idempotencyKey);

    /** Insert only. A conflicting owner/key must never overwrite an existing intention. */
    void reserve(UploadIntent intent);

    /** Compare current attempt and expired database lease; replace with a fresh attempt atomically. */
    boolean takeOver(UploadIntent previous, UploadIntent replacement);

    /** Verify live token and commit QUEUED plus one stable outbox event in the same transaction. */
    UploadIntent accept(UUID videoId, UUID attemptId, String bucket);
}
