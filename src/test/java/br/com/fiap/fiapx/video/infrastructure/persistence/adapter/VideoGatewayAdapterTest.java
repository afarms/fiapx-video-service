package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.exception.VideoConflictException;
import br.com.fiap.fiapx.video.core.exception.VideoPersistenceException;
import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class VideoGatewayAdapterTest {
    @Mock private SpringVideoRepository repository;
    private final VideoMapper mapper = new VideoMapper();
    private VideoGatewayAdapter adapter;
    private final Video video = new Video(UUID.randomUUID(), UUID.randomUUID(), "sample.mp4", "owner/original",
            100, Instant.parse("2026-09-23T12:00:00Z"));

    @BeforeEach
    void setUp() {
        adapter = new VideoGatewayAdapter(repository, mapper);
    }

    @Test
    void insertsMappedNewEntityAndFlushesWithinRepositoryCall() {
        adapter.insert(video);
        var captor = ArgumentCaptor.forClass(VideoEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        assertEquals(video, mapper.toDomain(captor.getValue()));
        assertTrue(captor.getValue().isNew());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void returnsDomainRatherThanJpaEntityForOwnedRecord() {
        when(repository.findByIdAndOwnerId(video.id(), video.ownerId())).thenReturn(Optional.of(mapper.toEntity(video)));
        assertEquals(Optional.of(video), adapter.findByIdAndOwnerId(video.id(), video.ownerId()));
        verify(repository).findByIdAndOwnerId(video.id(), video.ownerId());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void alwaysPassesOwnerFilterAndPreservesEmptyResult() {
        UUID anotherOwner = UUID.randomUUID();
        when(repository.findByIdAndOwnerId(video.id(), anotherOwner)).thenReturn(Optional.empty());
        assertTrue(adapter.findByIdAndOwnerId(video.id(), anotherOwner).isEmpty());
        verify(repository).findByIdAndOwnerId(video.id(), anotherOwner);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void exposesCoreConflictOnConstraintFailureWithoutRetryingOrUpdating() {
        var failure = new DataIntegrityViolationException("duplicate id or key");
        when(repository.saveAndFlush(any(VideoEntity.class))).thenThrow(failure);
        var exception = assertThrows(VideoConflictException.class, () -> adapter.insert(video));
        assertSame(failure, exception.getCause());
        verify(repository).saveAndFlush(any(VideoEntity.class));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void exposesCoreFailureWhenInsertCannotReachDatabase() {
        var failure = new DataAccessResourceFailureException("unavailable");
        when(repository.saveAndFlush(any(VideoEntity.class))).thenThrow(failure);
        var exception = assertThrows(VideoPersistenceException.class, () -> adapter.insert(video));
        assertSame(failure, exception.getCause());
    }

    @Test
    void doesNotTurnReadFailureIntoNotFound() {
        var failure = new DataAccessResourceFailureException("unavailable");
        when(repository.findByIdAndOwnerId(video.id(), video.ownerId())).thenThrow(failure);
        var exception = assertThrows(VideoPersistenceException.class,
                () -> adapter.findByIdAndOwnerId(video.id(), video.ownerId()));
        assertSame(failure, exception.getCause());
    }

    @Test
    void doesNotHideInvalidPersistedState() {
        var entity = new VideoEntity(video.id(), video.ownerId(), "sample.mp4", "key", 1, "UNKNOWN", video.createdAt());
        when(repository.findByIdAndOwnerId(video.id(), video.ownerId())).thenReturn(Optional.of(entity));
        assertThrows(IllegalArgumentException.class, () -> adapter.findByIdAndOwnerId(video.id(), video.ownerId()));
    }

    @Test
    void rejectsMissingDependenciesAndArgumentsBeforeCallingRepository() {
        assertAll(() -> assertThrows(NullPointerException.class, () -> new VideoGatewayAdapter(null, mapper)),
                () -> assertThrows(NullPointerException.class, () -> new VideoGatewayAdapter(repository, null)),
                () -> assertThrows(NullPointerException.class, () -> adapter.insert(null)),
                () -> assertThrows(NullPointerException.class, () -> adapter.findByIdAndOwnerId(null, video.ownerId())),
                () -> assertThrows(NullPointerException.class, () -> adapter.findByIdAndOwnerId(video.id(), null)));
        verifyNoInteractions(repository);
    }
}
