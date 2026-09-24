package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.exception.VideoNotFoundException;
import br.com.fiap.fiapx.video.core.gateway.VideoGateway;
import java.util.Objects;
import java.util.UUID;

public class GetVideoUseCase {
    private final VideoGateway gateway;

    public GetVideoUseCase(VideoGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway is required");
    }

    /** ownerId must come from a caller that has already authenticated and authorized the account. */
    public Video execute(UUID id, UUID ownerId) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(ownerId, "ownerId is required");
        return gateway.findByIdAndOwnerId(id, ownerId).orElseThrow(VideoNotFoundException::new);
    }
}
