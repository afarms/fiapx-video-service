package br.com.fiap.fiapx.video.infrastructure.messaging;

import br.com.fiap.fiapx.video.infrastructure.persistence.entity.OutboxEntity;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringOutboxRepository;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {
    final SpringOutboxRepository repository = mock(SpringOutboxRepository.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final SqsClient client = mock(SqsClient.class);
    final String url = "https://sqs.us-east-1.amazonaws.com/000000000000/work";
    final OutboxDispatcher dispatcher = new OutboxDispatcher(repository, new TransactionTemplate(manager), client, url);
    final UUID id = UUID.randomUUID();
    final OutboxEntity event = new OutboxEntity(id, "{\"eventId\":\"" + id + "\"}", 0);
    @BeforeEach void transaction() { when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus()); }

    @Test void emptyOutboxDoesNotContactAws() {
        dispatcher.dispatch(); verifyNoInteractions(client);
        assertThrows(IllegalArgumentException.class, () -> new OutboxDispatcher(repository, new TransactionTemplate(manager), client, "http://bad"));
    }
    @Test void claimsBeforeSendingAndMarksOnlyAfterSqsSuccess() {
        when(repository.pending(1)).thenReturn(List.of(event), List.of()); dispatcher.dispatch();
        var order = inOrder(repository, manager, client);
        order.verify(manager).getTransaction(any()); order.verify(repository).pending(1);
        order.verify(repository).claim(eq(id), any()); order.verify(manager).commit(any());
        order.verify(client).sendMessage(any(SendMessageRequest.class));
        order.verify(manager).getTransaction(any()); order.verify(repository).published(eq(id), any());
        verify(repository, never()).retry(any(), any(), anyLong());
    }
    @Test void failureAndLostAcknowledgementKeepIdenticalBodyAndEventIdForRetry() {
        when(repository.pending(1)).thenReturn(List.of(event), List.of());
        when(client.sendMessage(any(SendMessageRequest.class))).thenThrow(new RuntimeException("network"));
        dispatcher.dispatch();
        verify(repository).retry(eq(id), any(), eq(5L)); verify(repository, never()).published(any(), any());
        when(repository.pending(1)).thenReturn(List.of(new OutboxEntity(id, event.getPayload(), 100)), List.of());
        doReturn(null).when(client).sendMessage(any(SendMessageRequest.class));
        when(repository.published(any(), any())).thenThrow(new TransactionSystemException("uncertain"));
        dispatcher.dispatch();
        verify(repository).retry(eq(id), any(), eq(300L));
        var captor = org.mockito.ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client, times(2)).sendMessage(captor.capture());
        assertEquals(captor.getAllValues().get(0).messageBody(), captor.getAllValues().get(1).messageBody());
        assertEquals(url, captor.getValue().queueUrl());
    }
    @Test void limitsEachTickWithoutDiscardingBacklog() {
        when(repository.pending(1)).thenReturn(List.of(event)); dispatcher.dispatch();
        verify(client, times(10)).sendMessage(any(SendMessageRequest.class));
    }
}
