package br.com.fiap.fiapx.video.infrastructure.persistence.entity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoEntityTest {
    @Test
    void assignedUuidStillRepresentsInsertUntilPersistenceCallback() {
        UUID id = UUID.randomUUID();
        var entity = new VideoEntity(id, UUID.randomUUID(), "video.mp4", "key", 1, "UPLOADING", Instant.now());
        assertTrue(entity.isNew());
        entity.markNotNew();
        assertFalse(entity.isNew());
        assertEquals(id, entity.getId());
    }

    @Test
    void jpaHydrationCallbackMarksInstanceAsExisting() {
        var entity = new VideoEntity();
        entity.markNotNew();
        assertFalse(entity.isNew());
    }
}
