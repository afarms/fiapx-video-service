package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.core.usecase.CleanupExpiredResultsUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExpiredResultCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(ExpiredResultCleanup.class);
    private final CleanupExpiredResultsUseCase cleanup;
    public ExpiredResultCleanup(CleanupExpiredResultsUseCase cleanup) { this.cleanup = cleanup; }
    @Scheduled(scheduler = "resultCleanupScheduler", fixedDelayString = "${download.cleanup-delay-ms:60000}",
            initialDelayString = "${download.cleanup-delay-ms:60000}")
    public void poll() {
        try { cleanup.execute(); }
        catch (RuntimeException failure) { LOG.warn("Result cleanup deferred; durable claims remain recoverable"); }
    }
}
