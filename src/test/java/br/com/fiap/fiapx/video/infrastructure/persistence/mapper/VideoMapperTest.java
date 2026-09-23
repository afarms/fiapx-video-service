package br.com.fiap.fiapx.video.infrastructure.persistence.mapper;

import static org.junit.jupiter.api.Assertions.*;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class VideoMapperTest {
    private final VideoMapper mapper = new VideoMapper();
    private final Video video = new Video(UUID.randomUUID(), UUID.randomUUID(), "sample.MP4", "owner/original",
            100_000_000, Instant.parse("2026-09-23T12:00:00.123456Z"));

    @Test
    void mapsEveryFieldToNewEntityAndBackWithoutChangingIdentityOrTime() {
        var entity = mapper.toEntity(video);
        assertAll(() -> assertEquals(video.id(), entity.getId()),
                () -> assertEquals(video.ownerId(), entity.getOwnerId()),
                () -> assertEquals(video.originalName(), entity.getOriginalName()),
                () -> assertEquals(video.originalObjectKey(), entity.getOriginalObjectKey()),
                () -> assertEquals(video.sizeBytes(), entity.getSizeBytes()),
                () -> assertEquals(video.status(), entity.getStatus()),
                () -> assertEquals(video.createdAt(), entity.getCreatedAt()),
                () -> assertTrue(entity.isNew()),
                () -> assertEquals(video, mapper.toDomain(entity)));
    }

    @Test
    void doesNotReuseMutableJpaInstances() {
        assertNotSame(mapper.toEntity(video), mapper.toEntity(video));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"COMPLETED", "FAILED", "UNKNOWN"})
    void rejectsStatusThatInitialDomainCannotRepresent(String status) {
        var entity = entity(status, 1);
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(entity));
    }

    @Test
    void revalidatesDomainInvariantsWhenReadingPersistence() {
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(entity("UPLOADING", 0)));
    }

    @Test
    void refusesNullInputs() {
        assertAll(() -> assertThrows(NullPointerException.class, () -> mapper.toEntity(null)),
                () -> assertThrows(NullPointerException.class, () -> mapper.toDomain(null)));
    }

    private VideoEntity entity(String status, long size) {
        return new VideoEntity(video.id(), video.ownerId(), video.originalName(), video.originalObjectKey(),
                size, status, video.createdAt());
    }
}
