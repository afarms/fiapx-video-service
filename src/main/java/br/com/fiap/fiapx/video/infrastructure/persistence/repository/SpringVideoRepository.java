package br.com.fiap.fiapx.video.infrastructure.persistence.repository;

import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringVideoRepository extends JpaRepository<VideoEntity, UUID> {
    Optional<VideoEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
}
