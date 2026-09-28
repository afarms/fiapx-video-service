package br.com.fiap.fiapx.video.core.gateway;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;

public interface ResultCleanupStorageGateway {
    /** Delete this exact result. Confirmed absence is success; other failures must throw. */
    void delete(DownloadArtifact artifact);
}
