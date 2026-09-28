package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.domain.*;
import br.com.fiap.fiapx.video.core.exception.*;
import br.com.fiap.fiapx.video.core.gateway.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public class UploadVideoUseCase {
    private final UploadGateway uploads;
    private final OriginalStorageGateway storage;
    private final String bucket;
    private final Duration lease;

    public UploadVideoUseCase(UploadGateway uploads, OriginalStorageGateway storage, String bucket, Duration lease) {
        this.uploads = Objects.requireNonNull(uploads);
        this.storage = Objects.requireNonNull(storage);
        this.bucket = Objects.requireNonNull(bucket);
        this.lease = Objects.requireNonNull(lease);
        if (bucket.isBlank() || lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("Invalid upload configuration");
    }

    public Video execute(UUID owner, UUID key, String name, long size, String hash, Path file) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(key);
        Objects.requireNonNull(file);
        var now = uploads.now();
        var existing = uploads.findByOwnerAndKey(owner, key);
        UploadIntent intent;
        if (existing.isPresent()) {
            var previous = existing.get();
            if (!previous.matches(name, size, hash)) throw new UploadException(UploadException.Reason.CONTENT_CONFLICT);
            if (previous.acceptedAt() != null) return previous.video().queued();
            if (previous.activeAt(now)) throw new UploadException(UploadException.Reason.IN_PROGRESS);
            var attempt = UUID.randomUUID();
            intent = new UploadIntent(new Video(previous.video().id(), owner, name,
                    UploadIntent.objectKey(owner, previous.video().id(), attempt), size, previous.video().createdAt()),
                    key, hash, attempt, now.plus(lease), null);
            if (!uploads.takeOver(previous, intent)) throw new UploadException(UploadException.Reason.IN_PROGRESS);
        } else {
            var id = UUID.randomUUID();
            var attempt = UUID.randomUUID();
            intent = new UploadIntent(new Video(id, owner, name, UploadIntent.objectKey(owner, id, attempt), size, now),
                    key, hash, attempt, now.plus(lease), null);
            try {
                uploads.reserve(intent);
            } catch (VideoConflictException conflict) {
                // A concurrent request owns the key; never overwrite it or start a second S3 write.
                throw new UploadException(UploadException.Reason.IN_PROGRESS);
            }
        }
        storage.put(intent.video().originalObjectKey(), file, hash);
        try {
            return uploads.accept(intent.video().id(), intent.attemptId(), bucket).video().queued();
        } catch (VideoPersistenceException uncertainCommit) {
            // Reconcile a lost commit response; never compensate by deleting a possibly accepted object.
            var confirmed = uploads.findByOwnerAndKey(owner, key);
            if (confirmed.isPresent() && confirmed.get().acceptedAt() != null) return confirmed.get().video().queued();
            throw uncertainCommit;
        }
    }
}
