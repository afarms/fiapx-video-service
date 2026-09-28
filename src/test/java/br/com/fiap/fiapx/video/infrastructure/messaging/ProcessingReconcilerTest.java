package br.com.fiap.fiapx.video.infrastructure.messaging;

import br.com.fiap.fiapx.video.infrastructure.config.BeanConfig;
import br.com.fiap.fiapx.video.infrastructure.persistence.entity.OutboxEntity;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessingReconcilerTest {
    final SpringOutboxRepository repository = mock(SpringOutboxRepository.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final ProcessingReconciler reconciler = new BeanConfig().processingReconciler(repository, manager);

    @Test void emptyScanAndBatchCommitUseAnIndependentBoundedTransaction() {
        when(manager.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            assertEquals(10, definition.getTimeout());
            return new SimpleTransactionStatus();
        });
        assertEquals(0, reconciler.reconcile());
        UUID id = UUID.randomUUID();
        when(repository.awaitingResult()).thenReturn(List.of(new OutboxEntity(id, "original", 1)));
        assertEquals(1, reconciler.reconcile());
        var order = inOrder(repository, manager);
        order.verify(repository).reschedule(id);
        order.verify(manager).commit(any());
    }

    @Test void failedSchedulingOrUncertainCommitDoesNotReportSuccess() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        UUID id = UUID.randomUUID();
        when(repository.awaitingResult()).thenReturn(List.of(new OutboxEntity(id, "original", 1)));
        when(repository.reschedule(id)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, reconciler::reconcile);
        verify(manager).rollback(any());
        doReturn(1).when(repository).reschedule(id);
        doThrow(new TransactionSystemException("commit uncertain")).when(manager).commit(any());
        assertThrows(TransactionSystemException.class, reconciler::reconcile);
    }
}
