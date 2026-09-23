package br.com.fiap.fiapx.video.infrastructure.persistence.mapper;

import br.com.fiap.fiapx.video.core.domain.Video;
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
        if (!"UPLOADING".equals(entity.getStatus())) {
            throw new IllegalArgumentException("Unsupported persisted video status");
        }
        return new Video(entity.getId(), entity.getOwnerId(), entity.getOriginalName(),
                entity.getOriginalObjectKey(), entity.getSizeBytes(), entity.getCreatedAt());
    }
}
