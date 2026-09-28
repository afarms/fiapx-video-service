package br.com.fiap.fiapx.video.infrastructure.messaging;

import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringOutboxRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Reuses the durable request even when no transport message survives. */
public final class ProcessingReconciler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProcessingReconciler.class);
    private final SpringOutboxRepository repository;
    private final TransactionTemplate transactions;

    public ProcessingReconciler(SpringOutboxRepository repository, TransactionTemplate transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${upload.processing-reconcile-delay-ms:60000}")
    public int reconcile() {
        var ids = transactions.execute(tx -> {
            var pending = repository.awaitingResult();
            for (var event : pending) repository.reschedule(event.getEventId());
            return pending.stream().map(event -> event.getEventId()).toList();
        });
        for (var id : ids) LOG.warn("Processing result overdue; request rescheduled; eventId={}", id);
        return ids.size();
    }
}
