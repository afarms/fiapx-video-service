package br.com.fiap.fiapx.video.core.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UploadIntentTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();
    private final Instant created = Instant.parse("2026-09-27T12:00:00Z");
    private final Instant lease = created.plusSeconds(300);
    private final String hash = "abcdef01".repeat(8);

    private Video video() {
        return new Video(id, owner, "sample.mp4", UploadIntent.objectKey(owner, id, attempt), 10, created);
    }

    private UploadIntent intent() {
        return new UploadIntent(video(), key, hash, attempt, lease, null);
    }

    @Test
    void comparesEveryFingerprintFieldExactly() {
        assertTrue(intent().matches("sample.mp4", 10, hash));
        assertFalse(intent().matches("SAMPLE.mp4", 10, hash));
        assertFalse(intent().matches("sample.mp4", 11, hash));
        assertFalse(intent().matches("sample.mp4", 10, "0".repeat(64)));
        assertFalse(intent().matches(null, 10, hash));
    }

    @Test
    void leaseExpiresAtExactDeadlineButClientKeyDoesNot() {
        assertTrue(intent().activeAt(lease.minusNanos(1)));
        assertFalse(intent().activeAt(lease));
        assertFalse(intent().activeAt(lease.plusSeconds(86400)));
        assertEquals(key, intent().idempotencyKey());
        assertThrows(NullPointerException.class, () -> intent().activeAt(null));
    }

    @Test
    void acceptancePreservesIdentityHashAndOriginalAndStopsTheAttempt() {
        var accepted = intent().accept(attempt, created.plusSeconds(1));
        assertEquals("QUEUED", accepted.video().status());
        assertEquals(id, accepted.video().id());
        assertEquals(key, accepted.idempotencyKey());
        assertEquals(hash, accepted.sha256());
        assertEquals(attempt, accepted.attemptId());
        assertEquals(lease, accepted.leaseUntil());
        assertEquals(created.plusSeconds(1), accepted.acceptedAt());
        assertEquals(video().originalObjectKey(), accepted.video().originalObjectKey());
        assertFalse(accepted.activeAt(created.plusSeconds(2)));
        assertThrows(IllegalStateException.class, () -> accepted.accept(attempt, created.plusSeconds(2)));
    }

    @Test
    void refusesExpiredOrForeignAttemptAcceptance() {
        assertThrows(IllegalStateException.class, () -> intent().accept(UUID.randomUUID(), created));
        assertThrows(IllegalStateException.class, () -> intent().accept(null, created));
        assertThrows(IllegalStateException.class, () -> intent().accept(attempt, lease));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "G00000000000000000000000000000000000000000000000000000000000000000"})
    void rejectsInvalidFingerprint(String value) {
        assertThrows(IllegalArgumentException.class, () -> new UploadIntent(video(), key, value, attempt, lease, null));
    }

    @Test
    void requiresCompleteIntentionAndConsistentTimes() {
        assertThrows(NullPointerException.class, () -> new UploadIntent(null, key, hash, attempt, lease, null));
        assertThrows(NullPointerException.class, () -> new UploadIntent(video(), null, hash, attempt, lease, null));
        assertThrows(NullPointerException.class, () -> new UploadIntent(video(), key, hash, null, lease, null));
        assertThrows(NullPointerException.class, () -> new UploadIntent(video(), key, hash, attempt, null, null));
        assertThrows(IllegalArgumentException.class, () -> new UploadIntent(video(), key, hash, attempt, created.minusSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> new UploadIntent(video(), key, hash, attempt, lease, created));
        assertThrows(IllegalArgumentException.class, () -> new UploadIntent(video().queued(), key, hash, attempt, lease, null));
        assertThrows(IllegalArgumentException.class, () -> new UploadIntent(video().queued(), key, hash, attempt, lease, created.minusSeconds(1)));
    }

    @Test
    void rejectsKeysOutsideTheExactAttemptNamespace() {
        var wrong = new Video(id, owner, "sample.mp4", "originals/somewhere-else", 10, created);
        assertThrows(IllegalArgumentException.class, () -> new UploadIntent(wrong, key, hash, attempt, lease, null));
        assertThrows(NullPointerException.class, () -> UploadIntent.objectKey(null, id, attempt));
        assertThrows(NullPointerException.class, () -> UploadIntent.objectKey(owner, null, attempt));
        assertThrows(NullPointerException.class, () -> UploadIntent.objectKey(owner, id, null));
    }
}
