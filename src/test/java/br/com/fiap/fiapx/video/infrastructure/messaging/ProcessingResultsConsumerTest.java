package br.com.fiap.fiapx.video.infrastructure.messaging;

import br.com.fiap.fiapx.video.core.gateway.ProcessingResultsGateway;
import java.util.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import static br.com.fiap.fiapx.video.ProcessingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProcessingResultsConsumerTest {
    final SqsClient sqs=mock(SqsClient.class);
    final ProcessingResultsGateway gateway=mock(ProcessingResultsGateway.class);
    final String queue="https://sqs.us-east-1.amazonaws.com/123456789012/results";
    final ProcessingResultsConsumer consumer=new ProcessingResultsConsumer(sqs,queue,new ProcessingResultDecoder(JSON,BUCKET),gateway);
    final String body=JSON.writeValueAsString(envelope("ProcessingFailed",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2));
    void delivery(String body) { when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
            .messages(Message.builder().body(body).messageId("delivery").receiptHandle("current").build()).build()); }
    @BeforeEach void setup() { delivery(body); }
    @Test void everyDurableOutcomeAcksOnlyAfterTransactionReturns() {
        for (var outcome:ProcessingResultsGateway.Outcome.values()) {
            when(gateway.apply(any())).thenReturn(outcome); consumer.poll();
        }
        var order=inOrder(gateway,sqs);
        order.verify(gateway).apply(any()); order.verify(sqs).deleteMessage(argThat((DeleteMessageRequest r)->r.receiptHandle().equals("current") && r.queueUrl().equals(queue)));
        verify(sqs,times(3)).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs,times(3)).receiveMessage(argThat((ReceiveMessageRequest r)->r.maxNumberOfMessages()==1 && r.visibilityTimeout()==120));
    }
    @Test void invalidConflictDatabaseOrUncertainCommitNeverAck() {
        consumer.poll();
        when(gateway.apply(any())).thenThrow(new IllegalStateException("uncertain commit")); consumer.poll();
        delivery("{}"); consumer.poll(); verify(gateway,times(2)).apply(any());
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(new IllegalStateException("network")); consumer.poll();
        verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
    }
    @Test void failedAckRedeliveryIsStillHandledAndNoConcurrentPollOccurs() {
        when(gateway.apply(any())).thenAnswer(call->{consumer.poll(); return ProcessingResultsGateway.Outcome.DUPLICATE;});
        when(sqs.deleteMessage(any(DeleteMessageRequest.class))).thenThrow(new IllegalStateException("uncertain delete"));
        consumer.poll(); consumer.poll(); verify(sqs,times(2)).receiveMessage(any(ReceiveMessageRequest.class));
        verify(gateway,times(2)).apply(any());
    }
    @Test void stopAndEmptyQueueDoNotProcess() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build()); consumer.poll();
        consumer.close(); consumer.poll(); verifyNoInteractions(gateway);
        var second=new ProcessingResultsConsumer(sqs,queue,new ProcessingResultDecoder(JSON,BUCKET),gateway);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(call->{second.close(); return ReceiveMessageResponse.builder()
                .messages(Message.builder().body(body).build()).build();});
        second.poll(); verifyNoInteractions(gateway);
        assertThrows(IllegalArgumentException.class,()->new ProcessingResultsConsumer(sqs,null,null,gateway));
        assertThrows(IllegalArgumentException.class,()->new ProcessingResultsConsumer(sqs,"http://other",null,gateway));
    }
}
