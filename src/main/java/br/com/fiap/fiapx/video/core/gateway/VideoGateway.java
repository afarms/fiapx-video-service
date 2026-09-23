package br.com.fiap.fiapx.video.core.gateway;

import br.com.fiap.fiapx.video.core.domain.Video;
import java.util.Optional;
import java.util.UUID;

public interface VideoGateway {
    void insert(Video video);

    Optional<Video> findByIdAndOwnerId(UUID id, UUID ownerId);
}
