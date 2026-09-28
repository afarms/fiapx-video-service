package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import java.io.InputStream;

/** Transport boundary. Abort must discard an unfinished body without draining it. */
public interface DownloadStorage {
    Body open(DownloadArtifact artifact);
    interface Body {
        InputStream input();
        void abort();
    }
}
