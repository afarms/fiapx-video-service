package br.com.fiap.fiapx.video.domain;

import java.util.Optional;
import java.util.UUID;

public interface VideoRepository {
    void insert(Video video);

    Optional<Video> findByIdAndOwnerId(UUID id, UUID ownerId);
}
