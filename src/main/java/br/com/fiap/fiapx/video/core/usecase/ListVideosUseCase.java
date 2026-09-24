package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.domain.VideoPage;
import br.com.fiap.fiapx.video.core.domain.VideoPageRequest;
import br.com.fiap.fiapx.video.core.gateway.VideoGateway;
import java.util.Objects;
import java.util.UUID;

public class ListVideosUseCase {
    private final VideoGateway gateway;

    public ListVideosUseCase(VideoGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway is required");
    }

    /** ownerId must come from a caller that has already authenticated and authorized the account. */
    public VideoPage execute(UUID ownerId, VideoPageRequest request) {
        Objects.requireNonNull(ownerId, "ownerId is required");
        Objects.requireNonNull(request, "request is required");
        return gateway.findByOwnerId(ownerId, request);
    }
}
