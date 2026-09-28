package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import br.com.fiap.fiapx.video.core.domain.ProcessingResultEvent;
import br.com.fiap.fiapx.video.core.gateway.ProcessingResultsGateway;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class ProcessingResultsAdapter implements ProcessingResultsGateway {
    private final SpringVideoRepository repository;
    private final VideoMapper videos;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    public ProcessingResultsAdapter(SpringVideoRepository repository,VideoMapper videos,TransactionTemplate tx,JsonMapper json) {
        this.repository=repository; this.videos=videos; this.tx=tx; this.json=json;
    }
    public Outcome apply(ProcessingResultEvent event) {
        String canonical=json.writeValueAsString(event);
        return tx.execute(transaction->{
            var row=repository.lockProcessing(event.videoId()).orElseThrow(()->new IllegalArgumentException("Unknown video"));
            if (!row.getOwnerId().equals(event.ownerId()) || row.getAcceptedAt()==null
                    || !event.correlationId().toString().equals(repository.processingCorrelation(event.videoId())))
                throw new IllegalArgumentException("Conflicting accepted work");
            String recorded=repository.processingInbox(event.eventId());
            if (recorded!=null) {
                if (!recorded.equals(canonical)) throw new IllegalArgumentException("Conflicting event identity");
                return Outcome.DUPLICATE;
            }
            boolean apply=event.shouldApply(videos.toDomain(row),row.getProcessingVersion());
            if (apply) {
                var r=event.result();
                int changed=repository.applyProcessing(event.videoId(),event.status().name(),event.version(),event.attemptId(),(int)event.attempt(),
                        event.completedAt(),event.expiresAt(),event.failedAt(),event.failureCode(),r==null?null:r.bucket(),r==null?null:r.objectKey(),
                        r==null?null:r.sizeBytes(),r==null?null:r.sha256(),r==null?null:(int)r.frameCount());
                if (changed!=1) throw new IllegalStateException("Result update failed");
            }
            var outcome=apply?Outcome.APPLIED:Outcome.IGNORED;
            if (repository.insertProcessingInbox(event.eventId(),event.videoId(),event.version(),canonical,outcome.name())!=1)
                throw new IllegalArgumentException("Concurrent conflicting event identity");
            return outcome;
        });
    }
}
