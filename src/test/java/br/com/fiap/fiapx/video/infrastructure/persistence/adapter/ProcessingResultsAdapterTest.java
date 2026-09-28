package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import br.com.fiap.fiapx.video.core.domain.*;
import br.com.fiap.fiapx.video.core.gateway.ProcessingResultsGateway.Outcome;
import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static br.com.fiap.fiapx.video.ProcessingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProcessingResultsAdapterTest {
    final SpringVideoRepository repository=mock(SpringVideoRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final TransactionStatus status=mock(TransactionStatus.class);
    final ProcessingResultsAdapter adapter=new ProcessingResultsAdapter(repository,new VideoMapper(),new TransactionTemplate(manager),JSON);
    final UUID id=UUID.randomUUID(),owner=UUID.randomUUID(),correlation=UUID.randomUUID();
    final ProcessingResultEvent event=decode(envelope("ProcessingCompleted",id,owner,correlation,2));
    VideoEntity row;
    @BeforeEach void setup() {
        row=new VideoEntity(id,owner,"sample.mp4","private",100,"QUEUED",NOW,UUID.randomUUID(),"a".repeat(64),UUID.randomUUID(),NOW.plusSeconds(300),NOW);
        when(repository.lockProcessing(id)).thenReturn(Optional.of(row));
        when(repository.processingCorrelation(id)).thenReturn(correlation.toString());
        when(manager.getTransaction(any())).thenReturn(status);
        when(repository.applyProcessing(any(),anyString(),anyLong(),any(),anyInt(),nullable(Instant.class),nullable(Instant.class),nullable(Instant.class),
                nullable(String.class),nullable(String.class),nullable(String.class),nullable(Long.class),nullable(String.class),nullable(Integer.class))).thenReturn(1);
        when(repository.insertProcessingInbox(any(),any(),anyLong(),anyString(),anyString())).thenReturn(1);
    }
    @Test void appliesReferenceAndInboxThenCommitsBeforeReturning() {
        assertEquals(Outcome.APPLIED,adapter.apply(event)); var r=event.result();
        var order=inOrder(repository,manager);
        order.verify(repository).lockProcessing(id);
        order.verify(repository).applyProcessing(id,"COMPLETED",2,event.attemptId(),1,NOW,NOW.plusSeconds(86400),null,null,
                r.bucket(),r.objectKey(),100L,r.sha256(),1);
        order.verify(repository).insertProcessingInbox(event.eventId(),id,2,JSON.writeValueAsString(event),"APPLIED");
        order.verify(manager).commit(status);
    }
    @Test void duplicatesAreVerifiedAndConflictingIdentityRollsBack() {
        when(repository.processingInbox(event.eventId())).thenReturn(JSON.writeValueAsString(event));
        assertEquals(Outcome.DUPLICATE,adapter.apply(event));
        verify(repository,never()).insertProcessingInbox(any(),any(),anyLong(),anyString(),anyString());
        when(repository.processingInbox(event.eventId())).thenReturn("different");
        assertThrows(IllegalArgumentException.class,()->adapter.apply(event)); verify(manager).rollback(status);
    }
    @Test void oldStartIsRecordedWithoutRegressingTerminalAndFailureHasNoStorage() {
        ReflectionTestUtils.setField(row,"status","COMPLETED"); ReflectionTestUtils.setField(row,"processingVersion",2L);
        var started=decode(envelope("ProcessingStarted",id,owner,correlation,1));
        assertEquals(Outcome.IGNORED,adapter.apply(started));
        verify(repository).insertProcessingInbox(started.eventId(),id,1,JSON.writeValueAsString(started),"IGNORED");
        ReflectionTestUtils.setField(row,"status","QUEUED"); ReflectionTestUtils.setField(row,"processingVersion",0L);
        var failed=decode(envelope("ProcessingFailed",id,owner,correlation,2));
        assertEquals(Outcome.APPLIED,adapter.apply(failed));
        verify(repository).applyProcessing(id,"FAILED",2,failed.attemptId(),1,null,null,NOW,"INVALID_MEDIA",null,null,null,null,null);
    }
    @Test void ownerAcceptanceCorrelationAndUnknownVideoAreRejected() {
        ReflectionTestUtils.setField(row,"ownerId",UUID.randomUUID()); assertThrows(IllegalArgumentException.class,()->adapter.apply(event));
        ReflectionTestUtils.setField(row,"ownerId",owner); ReflectionTestUtils.setField(row,"acceptedAt",null);
        assertThrows(IllegalArgumentException.class,()->adapter.apply(event));
        ReflectionTestUtils.setField(row,"acceptedAt",NOW); when(repository.processingCorrelation(id)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,()->adapter.apply(event));
        when(repository.lockProcessing(id)).thenReturn(Optional.empty()); assertThrows(IllegalArgumentException.class,()->adapter.apply(event));
        verify(repository,never()).insertProcessingInbox(any(),any(),anyLong(),anyString(),anyString());
    }
    @Test void missingUpdateConcurrentInboxCollisionAndCommitUncertaintyDoNotReturnReceipt() {
        when(repository.insertProcessingInbox(any(),any(),anyLong(),anyString(),anyString())).thenReturn(0);
        assertThrows(IllegalArgumentException.class,()->adapter.apply(event)); verify(manager).rollback(status);
        when(repository.insertProcessingInbox(any(),any(),anyLong(),anyString(),anyString())).thenReturn(1);
        doThrow(new IllegalStateException("uncertain commit")).when(manager).commit(status);
        assertThrows(IllegalStateException.class,()->adapter.apply(event));
        when(repository.applyProcessing(any(),anyString(),anyLong(),any(),anyInt(),nullable(Instant.class),nullable(Instant.class),nullable(Instant.class),
                nullable(String.class),nullable(String.class),nullable(String.class),nullable(Long.class),nullable(String.class),nullable(Integer.class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->adapter.apply(event));
    }
}
