package br.com.fiap.fiapx.video.infrastructure.messaging;

import br.com.fiap.fiapx.video.core.gateway.ProcessingResultsGateway;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

public final class ProcessingResultsConsumer implements AutoCloseable {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProcessingResultsConsumer.class);
    private final SqsClient sqs;
    private final String queue;
    private final ProcessingResultDecoder decoder;
    private final ProcessingResultsGateway results;
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean stopped;
    public ProcessingResultsConsumer(SqsClient sqs,String queue,ProcessingResultDecoder decoder,ProcessingResultsGateway results) {
        if (queue==null || !queue.matches("https://sqs\\.[a-z0-9-]+\\.amazonaws\\.com/[0-9]{12}/[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Configure a Standard result queue URL");
        this.sqs=sqs; this.queue=queue; this.decoder=decoder; this.results=results;
    }
    @Scheduled(fixedDelayString="${results.poll-delay-ms:1000}")
    public void poll() {
        if (stopped || !busy.compareAndSet(false,true)) return;
        try {
            var response=sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queue).maxNumberOfMessages(1)
                    .waitTimeSeconds(5).visibilityTimeout(120).build());
            for (var message:response.messages()) {
                if (stopped) return;
                try {
                    var outcome=results.apply(decoder.decode(message.body()));
                    if (outcome!=null) sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queue).receiptHandle(message.receiptHandle()).build());
                } catch (IllegalArgumentException conflict) { LOG.warn("Invalid or conflicting processing result; messageId={}",message.messageId()); }
                catch (RuntimeException failure) { LOG.warn("Processing result deferred; messageId={}",message.messageId()); }
            }
        } catch (RuntimeException failure) { LOG.warn("Processing result receive unavailable"); }
        finally { busy.set(false); }
    }
    public void close() { stopped=true; }
}
