package br.com.fiap.fiapx.video.infrastructure.messaging;

import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringOutboxRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.util.*;

public class OutboxDispatcher {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OutboxDispatcher.class);
    private final SpringOutboxRepository repository;
    private final TransactionTemplate transactions;
    private final SqsClient client;
    private final String queueUrl;
    public OutboxDispatcher(SpringOutboxRepository repository, TransactionTemplate transactions, SqsClient client, String queueUrl) {
        this.repository = repository; this.transactions = transactions; this.client = client; this.queueUrl = queueUrl;
        if (!queueUrl.startsWith("https://sqs.us-east-1.amazonaws.com/")) throw new IllegalArgumentException("Configure the processing queue URL");
    }
    private record Publication(UUID id, UUID token, String body, long attempts) { }

    @Scheduled(fixedDelayString = "${upload.publisher-delay-ms:5000}")
    public void dispatch() {
        // One message per claim prevents a sequential batch from expiring before its last send.
        for (int count = 0; count < 10; count++) {
            var publication = transactions.execute(tx -> {
                var pending = repository.pending(1);
                if (pending.isEmpty()) return Optional.<Publication>empty();
                var event = pending.getFirst();
                UUID token = UUID.randomUUID();
                repository.claim(event.getEventId(), token);
                return Optional.of(new Publication(event.getEventId(), token, event.getPayload(), event.getAttempts() + 1));
            });
            if (publication.isEmpty()) return;
            var event = publication.get();
            try {
                client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(event.body()).build());
                transactions.executeWithoutResult(tx -> repository.published(event.id(), event.token()));
            } catch (RuntimeException failure) {
                // Uncertain publication is retried with the persisted eventId and identical body.
                LOG.warn("Outbox publication deferred; eventId={}", event.id());
                long delay = Math.min(300, 5L << (int) Math.min(6, event.attempts() - 1));
                transactions.executeWithoutResult(tx -> repository.retry(event.id(), event.token(), delay));
            }
        }
    }
}
