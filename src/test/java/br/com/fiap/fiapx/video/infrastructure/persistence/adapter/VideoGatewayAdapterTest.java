package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.domain.VideoPageRequest;
import br.com.fiap.fiapx.video.core.exception.VideoConflictException;
import br.com.fiap.fiapx.video.core.exception.VideoPersistenceException;
import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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

    @Test
    void listsMappedVideosWithOwnerFilterStableOrderingAndTotal() {
        var request = new VideoPageRequest(1, 2);
        var pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var older = new Video(UUID.randomUUID(), video.ownerId(), "older.mp4", "older/key", 5,
                video.createdAt().minusSeconds(1));
        when(repository.findByOwnerId(video.ownerId(), pageable)).thenReturn(
                new PageImpl<>(List.of(mapper.toEntity(video), mapper.toEntity(older)), pageable, 7));
        var result = adapter.findByOwnerId(video.ownerId(), request);
        assertEquals(List.of(video, older), result.items());
        assertEquals(1, result.page());
        assertEquals(2, result.size());
        assertEquals(7, result.totalElements());
        verify(repository).findByOwnerId(video.ownerId(), pageable);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void preservesEmptyPagesForAnotherOwnerAndBeyondTheLastPage() {
        var anotherOwner = UUID.randomUUID();
        var first = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var beyond = PageRequest.of(9, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(repository.findByOwnerId(anotherOwner, first)).thenReturn(new PageImpl<>(List.of(), first, 0));
        when(repository.findByOwnerId(video.ownerId(), beyond)).thenReturn(new PageImpl<>(List.of(), beyond, 21));
        var empty = adapter.findByOwnerId(anotherOwner, new VideoPageRequest(0, 20));
        var pastLast = adapter.findByOwnerId(video.ownerId(), new VideoPageRequest(9, 20));
        assertTrue(empty.items().isEmpty());
        assertEquals(0, empty.totalElements());
        assertTrue(pastLast.items().isEmpty());
        assertEquals(21, pastLast.totalElements());
        assertEquals(9, pastLast.page());
        verify(repository).findByOwnerId(anotherOwner, first);
        verify(repository).findByOwnerId(video.ownerId(), beyond);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void translatesListDatabaseFailureWithoutReturningAnEmptyPage() {
        var failure = new DataAccessResourceFailureException("unavailable");
        when(repository.findByOwnerId(any(), any())).thenThrow(failure);
        var exception = assertThrows(VideoPersistenceException.class,
                () -> adapter.findByOwnerId(video.ownerId(), new VideoPageRequest(0, 20)));
        assertSame(failure, exception.getCause());
        verify(repository).findByOwnerId(eq(video.ownerId()), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsMissingListArgumentsBeforeRepositoryAccess() {
        assertThrows(NullPointerException.class, () -> adapter.findByOwnerId(null, new VideoPageRequest(0, 20)));
        assertThrows(NullPointerException.class, () -> adapter.findByOwnerId(video.ownerId(), null));
        verifyNoInteractions(repository);
    }
}
