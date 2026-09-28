package br.com.fiap.fiapx.video.infrastructure.persistence.mapper;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.domain.VideoStatus;
import br.com.fiap.fiapx.video.core.domain.UploadIntent;
import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import java.util.Objects;

public class VideoMapper {
    public VideoEntity toEntity(Video video) {
        Objects.requireNonNull(video, "video is required");
        return new VideoEntity(video.id(), video.ownerId(), video.originalName(), video.originalObjectKey(),
                video.sizeBytes(), video.status(), video.createdAt());
    }

    public Video toDomain(VideoEntity entity) {
        Objects.requireNonNull(entity, "entity is required");
        if (entity.getStatus() == null) {
            throw new IllegalArgumentException("Unsupported persisted video status");
        }
        return new Video(entity.getId(), entity.getOwnerId(), entity.getOriginalName(),
                entity.getOriginalObjectKey(), entity.getSizeBytes(), entity.getCreatedAt(),
                VideoStatus.valueOf(entity.getStatus()), entity.getCompletedAt(), entity.getExpiresAt(),
                entity.getFailedAt(), entity.getFailureCode());
    }

    public VideoEntity toUploadEntity(UploadIntent intent) {
        Objects.requireNonNull(intent, "intent is required");
        var video = intent.video();
        return new VideoEntity(video.id(), video.ownerId(), video.originalName(), video.originalObjectKey(),
                video.sizeBytes(), video.status(), video.createdAt(), intent.idempotencyKey(), intent.sha256(),
                intent.attemptId(), intent.leaseUntil(), intent.acceptedAt());
    }

    public UploadIntent toUploadIntent(VideoEntity entity) {
        return new UploadIntent(toDomain(entity), entity.getIdempotencyKey(), entity.getContentSha256(),
                entity.getUploadAttemptId(), entity.getUploadLeaseUntil(), entity.getAcceptedAt());
    }
}
