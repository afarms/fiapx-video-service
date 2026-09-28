package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.gateway.DownloadGateway;
import java.time.Duration;
import java.util.UUID;

/** Every admission checks the current account before acquiring a durable reservation. */
public class DownloadVideoUseCase {
    private final AuthorizeVideoAccessUseCase authorize;
    private final DownloadGateway downloads;
    private final Duration lease, maximum;

    public DownloadVideoUseCase(AuthorizeVideoAccessUseCase authorize, DownloadGateway downloads,
                               Duration lease, Duration maximum) {
        this.authorize = authorize;
        this.downloads = downloads;
        this.lease = lease;
        this.maximum = maximum;
    }

    public DownloadGateway.Lease execute(UUID video, String token, UUID subject, long version) {
        return downloads.reserve(video, authorize.execute(token, subject, version), lease, maximum);
    }
}
