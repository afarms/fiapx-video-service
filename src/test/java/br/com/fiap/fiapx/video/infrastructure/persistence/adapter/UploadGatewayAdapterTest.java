package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import br.com.fiap.fiapx.video.core.domain.*;
import br.com.fiap.fiapx.video.core.exception.*;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadGatewayAdapterTest {
    final SpringVideoRepository repository = mock(SpringVideoRepository.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final VideoMapper mapper = new VideoMapper();
    final JsonMapper json = JsonMapper.builder().build();
    final UploadGatewayAdapter adapter = new UploadGatewayAdapter(repository, mapper, new TransactionTemplate(manager), json);
    final Instant now = Instant.parse("2026-09-27T12:00:00Z");
    final UUID owner = UUID.randomUUID(), video = UUID.randomUUID(), key = UUID.randomUUID();

    @BeforeEach void transaction() { when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus()); }
    UploadIntent intent(UUID id, UUID account, UUID intention, String name) {
        UUID token = UUID.randomUUID();
        return new UploadIntent(new Video(id, account, name, UploadIntent.objectKey(account, id, token), 10, now),
                intention, "a".repeat(64), token, now.plusSeconds(300), null);
    }
    UploadIntent intent() { return intent(video, owner, key, "test.mp4"); }

    @Test void readsDatabaseTimeAndOwnerScopedIntentions() {
        var intent = intent();
        when(repository.databaseTime()).thenReturn(now);
        when(repository.findByOwnerIdAndIdempotencyKey(owner, key)).thenReturn(Optional.of(mapper.toUploadEntity(intent)));
        assertEquals(now, adapter.now());
        assertEquals(Optional.of(intent), adapter.findByOwnerAndKey(owner, key));
        assertTrue(adapter.findByOwnerAndKey(UUID.randomUUID(), key).isEmpty());
    }
    @Test void reservesMetadataAndRecoverableAttemptInOneTransaction() {
        var intent = intent(); adapter.reserve(intent);
        verify(repository).saveAndFlush(any());
        verify(repository).recordAttempt(intent.attemptId(), video, intent.video().originalObjectKey(), intent.leaseUntil().plusSeconds(900));
        verify(manager).commit(any()); verify(manager, never()).rollback(any());
    }
    @Test void uniqueConflictAndDatabaseFailureRollBackAndRemainDistinct() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("conflict"));
        assertThrows(VideoConflictException.class, () -> adapter.reserve(intent()));
        verify(manager).rollback(any()); verify(repository, never()).recordAttempt(any(), any(), any(), any());
        doThrow(new DataAccessResourceFailureException("offline")).when(repository).saveAndFlush(any());
        assertThrows(VideoPersistenceException.class, () -> adapter.reserve(intent()));
        doThrow(new TransactionSystemException("commit response lost")).when(manager).commit(any());
        reset(repository);
        assertThrows(VideoPersistenceException.class, () -> adapter.reserve(intent()));
    }
    @Test void takeoverRecordsNewAttemptOnlyWhenConditionalUpdateWins() {
        var old = intent(); var next = intent();
        assertFalse(adapter.takeOver(old, next));
        verify(repository, never()).recordAttempt(any(), any(), any(), any());
        when(repository.takeOver(video, old.attemptId(), next.attemptId(), next.video().originalObjectKey(), next.leaseUntil())).thenReturn(1);
        assertTrue(adapter.takeOver(old, next));
        verify(repository).recordAttempt(next.attemptId(), video, next.video().originalObjectKey(), next.leaseUntil().plusSeconds(900));
    }
    @Test void takeoverCannotChangeVideoOwnerIntentionOrFingerprint() {
        var old = intent();
        for (var next : List.of(intent(UUID.randomUUID(), owner, key, "test.mp4"),
                intent(video, UUID.randomUUID(), key, "test.mp4"), intent(video, owner, UUID.randomUUID(), "test.mp4"),
                intent(video, owner, key, "another.mp4")))
            assertThrows(IllegalArgumentException.class, () -> adapter.takeOver(old, next));
        verifyNoInteractions(repository);
    }
    @Test void acceptanceAndEnvelopeShareOneCommit() throws Exception {
        var pending = intent(); var accepted = pending.accept(pending.attemptId(), now.plusSeconds(1));
        when(repository.acceptAttempt(video, pending.attemptId())).thenReturn(1);
        when(repository.findById(video)).thenReturn(Optional.of(mapper.toUploadEntity(accepted)));
        assertEquals(accepted, adapter.accept(video, pending.attemptId(), "media"));
        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        var id = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(repository).enqueue(id.capture(), eq(video), eq(owner), body.capture(), eq(accepted.acceptedAt()));
        var event = json.readTree(body.getValue());
        assertEquals(id.getValue().toString(), event.get("eventId").asText());
        assertEquals(video.toString(), event.get("aggregateId").asText());
        assertEquals(owner.toString(), event.get("ownerId").asText());
        assertEquals("VideoProcessingRequested", event.get("eventType").asText());
        assertEquals(1, event.get("schemaVersion").asInt());
        assertEquals("media", event.get("payload").get("bucket").asText());
        assertEquals(accepted.sha256(), event.get("payload").get("sha256").asText());
        verify(manager).commit(any());
    }
    @Test void expiredAttemptDoesNotProduceEventAndEnqueueFailureRollsBack() {
        var intent = intent();
        assertThrows(UploadException.class, () -> adapter.accept(video, intent.attemptId(), "media"));
        verify(repository, never()).enqueue(any(), any(), any(), any(), any());
        when(repository.acceptAttempt(video, intent.attemptId())).thenReturn(1);
        when(repository.findById(video)).thenReturn(Optional.of(mapper.toUploadEntity(intent.accept(intent.attemptId(), now))));
        when(repository.enqueue(any(), any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThrows(VideoPersistenceException.class, () -> adapter.accept(video, intent.attemptId(), "media"));
        verify(manager, times(2)).rollback(any());
    }
    @Test void readAndCommitFailuresBecomeDependencyFailures() {
        when(repository.databaseTime()).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThrows(VideoPersistenceException.class, adapter::now);
        doThrow(new TransactionSystemException("uncertain")).when(manager).commit(any());
        assertThrows(VideoPersistenceException.class, () -> adapter.takeOver(intent(), intent()));
    }
}
