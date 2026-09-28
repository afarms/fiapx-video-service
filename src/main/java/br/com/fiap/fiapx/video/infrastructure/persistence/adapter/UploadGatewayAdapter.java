package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import br.com.fiap.fiapx.video.core.domain.UploadIntent;
import br.com.fiap.fiapx.video.core.exception.*;
import br.com.fiap.fiapx.video.core.gateway.UploadGateway;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public class UploadGatewayAdapter implements UploadGateway {
    private final SpringVideoRepository repository;
    private final VideoMapper mapper;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public UploadGatewayAdapter(SpringVideoRepository repository, VideoMapper mapper,
                                TransactionTemplate transactions, JsonMapper json) {
        this.repository = Objects.requireNonNull(repository);
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = Objects.requireNonNull(transactions);
        this.json = Objects.requireNonNull(json);
    }

    public Instant now() { return safely(repository::databaseTime); }

    public Optional<UploadIntent> findByOwnerAndKey(UUID owner, UUID key) {
        return safely(() -> repository.findByOwnerIdAndIdempotencyKey(owner, key).map(mapper::toUploadIntent));
    }

    public void reserve(UploadIntent intent) {
        try {
            transactions.executeWithoutResult(tx -> {
                repository.saveAndFlush(mapper.toUploadEntity(intent));
                recordAttempt(intent);
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new VideoConflictException(conflict);
        } catch (DataAccessException | TransactionException failure) {
            throw new VideoPersistenceException("Could not reserve upload", failure);
        }
    }

    public boolean takeOver(UploadIntent previous, UploadIntent replacement) {
        if (!previous.video().id().equals(replacement.video().id())
                || !previous.idempotencyKey().equals(replacement.idempotencyKey())
                || !previous.video().ownerId().equals(replacement.video().ownerId())
                || !previous.matches(replacement.video().originalName(), replacement.video().sizeBytes(), replacement.sha256())) {
            throw new IllegalArgumentException("Takeover must preserve the original intention");
        }
        return safely(() -> transactions.execute(tx -> {
            int changed = repository.takeOver(previous.video().id(), previous.attemptId(), replacement.attemptId(),
                    replacement.video().originalObjectKey(), replacement.leaseUntil());
            if (changed == 0) return false;
            recordAttempt(replacement);
            return true;
        }));
    }

    public UploadIntent accept(UUID videoId, UUID attemptId, String bucket) {
        return safely(() -> transactions.execute(tx -> {
            if (repository.acceptAttempt(videoId, attemptId) != 1) throw new UploadException(UploadException.Reason.IN_PROGRESS);
            var accepted = mapper.toUploadIntent(repository.findById(videoId).orElseThrow());
            var eventId = UUID.randomUUID();
            var video = accepted.video();
            String envelope = json.writeValueAsString(Map.of(
                    "eventId", eventId.toString(), "eventType", "VideoProcessingRequested", "schemaVersion", 1,
                    "aggregateId", videoId.toString(), "ownerId", video.ownerId().toString(),
                    "correlationId", eventId.toString(), "occurredAt", accepted.acceptedAt().toString(),
                    "payload", Map.of("bucket", bucket, "objectKey", video.originalObjectKey(),
                            "sizeBytes", video.sizeBytes(), "sha256", accepted.sha256(), "originalName", video.originalName())));
            repository.enqueue(eventId, videoId, video.ownerId(), envelope, accepted.acceptedAt());
            return accepted;
        }));
    }

    private void recordAttempt(UploadIntent intent) {
        repository.recordAttempt(intent.attemptId(), intent.video().id(), intent.video().originalObjectKey(),
                intent.leaseUntil().plusSeconds(900));
    }

    private <T> T safely(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException | TransactionException failure) {
            throw new VideoPersistenceException("Upload persistence unavailable", failure);
        }
    }
}
