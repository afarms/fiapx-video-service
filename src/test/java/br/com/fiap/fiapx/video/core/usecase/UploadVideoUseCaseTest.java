package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.domain.*;
import br.com.fiap.fiapx.video.core.exception.*;
import br.com.fiap.fiapx.video.core.gateway.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadVideoUseCaseTest {
    final UploadGateway gateway = mock(UploadGateway.class);
    final OriginalStorageGateway storage = mock(OriginalStorageGateway.class);
    final Instant now = Instant.parse("2026-09-27T12:00:00Z");
    final UUID owner = UUID.randomUUID(), key = UUID.randomUUID();
    final String hash = "a".repeat(64);
    final Path file = Path.of("test.mp4");
    final UploadVideoUseCase useCase = new UploadVideoUseCase(gateway, storage, "media", Duration.ofSeconds(300));
    final AtomicReference<UploadIntent> reserved = new AtomicReference<>();

    @BeforeEach void setup() {
        when(gateway.now()).thenReturn(now);
        when(gateway.findByOwnerAndKey(owner, key)).thenReturn(Optional.empty());
        doAnswer(call -> { reserved.set(call.getArgument(0)); return null; }).when(gateway).reserve(any());
        when(gateway.accept(any(), any(), eq("media"))).thenAnswer(call -> reserved.get().accept(call.getArgument(1), now.plusSeconds(1)));
    }
    Video execute() { return useCase.execute(owner, key, "test.mp4", 10, hash, file); }
    UploadIntent previous(boolean expired) {
        var id = UUID.randomUUID(); var attempt = UUID.randomUUID();
        return new UploadIntent(new Video(id, owner, "test.mp4", UploadIntent.objectKey(owner, id, attempt), 10,
                now.minusSeconds(600)), key, hash, attempt, expired ? now : now.plusSeconds(300), null);
    }

    @Test void acceptsOnlyAfterStorageAndAtomicConfirmation() {
        var result = execute();
        assertEquals("QUEUED", result.status());
        assertEquals(owner, result.ownerId());
        assertEquals(key, reserved.get().idempotencyKey());
        var order = inOrder(gateway, storage);
        order.verify(gateway).now(); order.verify(gateway).findByOwnerAndKey(owner, key);
        order.verify(gateway).reserve(reserved.get());
        order.verify(storage).put(result.originalObjectKey(), file, hash);
        order.verify(gateway).accept(result.id(), reserved.get().attemptId(), "media");
    }
    @Test void acceptedReplayIsStableAndScopedToOwner() {
        var pending = previous(false);
        var accepted = pending.accept(pending.attemptId(), now);
        when(gateway.findByOwnerAndKey(owner, key)).thenReturn(Optional.of(accepted));
        assertEquals(accepted.video(), execute());
        verifyNoInteractions(storage);
        verify(gateway, never()).reserve(any()); verify(gateway, never()).accept(any(), any(), any());
        UUID another = UUID.randomUUID();
        when(gateway.findByOwnerAndKey(another, key)).thenReturn(Optional.empty());
        useCase.execute(another, key, "test.mp4", 10, hash, file);
        assertEquals(another, reserved.get().video().ownerId());
        assertNotEquals(accepted.video().id(), reserved.get().video().id());
    }
    @Test void conflictsAreNotWrittenToStorage() {
        var pending = previous(false);
        when(gateway.findByOwnerAndKey(owner, key)).thenReturn(Optional.of(pending));
        assertEquals(UploadException.Reason.IN_PROGRESS, assertThrows(UploadException.class, this::execute).reason());
        assertEquals(UploadException.Reason.CONTENT_CONFLICT, assertThrows(UploadException.class,
                () -> useCase.execute(owner, key, "different.mp4", 10, hash, file)).reason());
        verifyNoInteractions(storage);
    }
    @Test void expiredAttemptRetainsIntentionButUsesNewObjectKey() {
        var old = previous(true);
        when(gateway.findByOwnerAndKey(owner, key)).thenReturn(Optional.of(old));
        when(gateway.takeOver(eq(old), any())).thenAnswer(call -> {reserved.set(call.getArgument(1)); return true;});
        var result = execute();
        assertEquals(old.video().id(), result.id()); assertEquals(old.video().createdAt(), result.createdAt());
        assertNotEquals(old.video().originalObjectKey(), result.originalObjectKey());
        assertEquals(old.idempotencyKey(), reserved.get().idempotencyKey());
    }
    @Test void lostTakeoverOrReservationRaceDoesNotPut() {
        var old = previous(true);
        when(gateway.findByOwnerAndKey(owner, key)).thenReturn(Optional.of(old));
        assertThrows(UploadException.class, this::execute);
        when(gateway.findByOwnerAndKey(owner, key)).thenReturn(Optional.empty());
        doThrow(new VideoConflictException(new RuntimeException())).when(gateway).reserve(any());
        assertEquals(UploadException.Reason.IN_PROGRESS, assertThrows(UploadException.class, this::execute).reason());
        verifyNoInteractions(storage);
    }
    @Test void failedPutIsNotAcceptedAndRemainsRecoverable() {
        doThrow(new UploadException(UploadException.Reason.UNAVAILABLE)).when(storage).put(any(), any(), any());
        assertThrows(UploadException.class, this::execute);
        assertNotNull(reserved.get());
        verify(gateway, never()).accept(any(), any(), any());
    }
    @Test void lostCommitResponseRecoversConfirmedReceipt() {
        when(gateway.accept(any(), any(), any())).thenThrow(new VideoPersistenceException("uncertain", new RuntimeException()));
        when(gateway.findByOwnerAndKey(owner, key)).thenAnswer(call -> reserved.get() == null
                ? Optional.empty() : Optional.of(reserved.get().accept(reserved.get().attemptId(), now.plusSeconds(1))));
        assertEquals("QUEUED", execute().status());
        verify(gateway, times(2)).findByOwnerAndKey(owner, key);
    }
    @Test void unconfirmedCommitNeverProducesAcceptance() {
        var uncertain = new VideoPersistenceException("uncertain", new RuntimeException());
        when(gateway.accept(any(), any(), any())).thenThrow(uncertain);
        assertSame(uncertain, assertThrows(VideoPersistenceException.class, this::execute));
        when(gateway.findByOwnerAndKey(owner, key)).thenAnswer(call -> Optional.ofNullable(reserved.get()));
        reserved.set(null);
        assertSame(uncertain, assertThrows(VideoPersistenceException.class, this::execute));
    }
    @Test void rejectsInvalidConfigurationAndInput() {
        for (Duration duration : List.of(Duration.ZERO, Duration.ofSeconds(-1)))
            assertThrows(IllegalArgumentException.class, () -> new UploadVideoUseCase(gateway, storage, "media", duration));
        assertThrows(IllegalArgumentException.class, () -> new UploadVideoUseCase(gateway, storage, " ", Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> useCase.execute(null, key, "test.mp4", 10, hash, file));
        assertThrows(NullPointerException.class, () -> useCase.execute(owner, null, "test.mp4", 10, hash, file));
        assertThrows(NullPointerException.class, () -> useCase.execute(owner, key, "test.mp4", 10, hash, null));
    }
}
