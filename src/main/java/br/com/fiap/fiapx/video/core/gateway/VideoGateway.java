package br.com.fiap.fiapx.video.core.gateway;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.domain.VideoPage;
import br.com.fiap.fiapx.video.core.domain.VideoPageRequest;
import java.util.Optional;
import java.util.UUID;

public interface VideoGateway {
    void insert(Video video);

    Optional<Video> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** Filter and count by owner before pagination; order by createdAt DESC, id DESC. */
    VideoPage findByOwnerId(UUID ownerId, VideoPageRequest request);
}
