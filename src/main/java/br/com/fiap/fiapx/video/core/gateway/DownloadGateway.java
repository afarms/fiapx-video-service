package br.com.fiap.fiapx.video.core.gateway;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** All methods commit before returning. No transaction may surround network streaming. */
public interface DownloadGateway {
    record Lease(UUID token, DownloadArtifact artifact, Instant validUntil, Instant deadline) {}
    record Cleanup(UUID token, DownloadArtifact artifact, Instant validUntil) {}
    // Caller must first authorize the current account; owner filtering is still enforced here.
    Lease reserve(UUID video, UUID owner, Duration lease, Duration maximumDuration);
    Optional<Instant> renew(UUID video, UUID token, Duration lease);
    void release(UUID video, UUID token);
    List<Cleanup> claimExpired(int limit, Duration lease);
    boolean deleted(UUID video, UUID token);
    boolean retryCleanup(UUID video, UUID token, Duration delay);
}
