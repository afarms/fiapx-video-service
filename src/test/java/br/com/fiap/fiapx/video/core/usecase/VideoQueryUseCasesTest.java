package br.com.fiap.fiapx.video.core.usecase;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.domain.VideoPage;
import br.com.fiap.fiapx.video.core.domain.VideoPageRequest;
import br.com.fiap.fiapx.video.core.exception.VideoNotFoundException;
import br.com.fiap.fiapx.video.core.exception.VideoPersistenceException;
import br.com.fiap.fiapx.video.core.gateway.VideoGateway;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VideoQueryUseCasesTest {
    @Mock private VideoGateway gateway;
    private GetVideoUseCase getVideo;
    private ListVideosUseCase listVideos;
    private final UUID owner = UUID.randomUUID();
    private final Video video = new Video(UUID.randomUUID(), owner, "sample.mp4", "original/key", 100,
            Instant.parse("2026-09-23T12:00:00Z"));
    private final VideoPageRequest request = new VideoPageRequest(0, 20);

    @BeforeEach
    void setUp() {
        getVideo = new GetVideoUseCase(gateway);
        listVideos = new ListVideosUseCase(gateway);
    }

    @Test
    void retrievesOwnedVideoWithMetadataAndStatus() {
        when(gateway.findByIdAndOwnerId(video.id(), owner)).thenReturn(Optional.of(video));
        var result = getVideo.execute(video.id(), owner);
        assertEquals(video, result);
        assertEquals("UPLOADING", result.status());
        verify(gateway).findByIdAndOwnerId(video.id(), owner);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void absentAndForeignVideosHaveIndistinguishableErrors() {
        var anotherOwner = UUID.randomUUID();
        var missingId = UUID.randomUUID();
        when(gateway.findByIdAndOwnerId(video.id(), anotherOwner)).thenReturn(Optional.empty());
        when(gateway.findByIdAndOwnerId(missingId, owner)).thenReturn(Optional.empty());
        var foreign = assertThrows(VideoNotFoundException.class, () -> getVideo.execute(video.id(), anotherOwner));
        var missing = assertThrows(VideoNotFoundException.class, () -> getVideo.execute(missingId, owner));
        assertEquals("Video not found", missing.getMessage());
        assertEquals(missing.getMessage(), foreign.getMessage());
        verify(gateway).findByIdAndOwnerId(video.id(), anotherOwner);
        verify(gateway).findByIdAndOwnerId(missingId, owner);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void listsEachOwnerUsingTheirOwnFilterAndPreservesPagination() {
        var anotherOwner = UUID.randomUUID();
        var owned = new VideoPage(List.of(video), 0, 20, 1);
        var empty = new VideoPage(List.of(), 0, 20, 0);
        when(gateway.findByOwnerId(owner, request)).thenReturn(owned);
        when(gateway.findByOwnerId(anotherOwner, request)).thenReturn(empty);
        assertEquals(owned, listVideos.execute(owner, request));
        assertEquals(empty, listVideos.execute(anotherOwner, request));
        verify(gateway).findByOwnerId(owner, request);
        verify(gateway).findByOwnerId(anotherOwner, request);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void preservesAnEmptyPageBeyondTheLastPageAndItsTotal() {
        var beyond = new VideoPageRequest(9, 20);
        var result = new VideoPage(List.of(), 9, 20, 21);
        when(gateway.findByOwnerId(owner, beyond)).thenReturn(result);
        assertEquals(result, listVideos.execute(owner, beyond));
    }

    @Test
    void persistenceFailuresAreNotAbsenceOrEmptyLists() {
        var failure = new VideoPersistenceException("unavailable", new RuntimeException());
        when(gateway.findByIdAndOwnerId(video.id(), owner)).thenThrow(failure);
        when(gateway.findByOwnerId(owner, request)).thenThrow(failure);
        assertSame(failure, assertThrows(VideoPersistenceException.class, () -> getVideo.execute(video.id(), owner)));
        assertSame(failure, assertThrows(VideoPersistenceException.class, () -> listVideos.execute(owner, request)));
        verify(gateway).findByIdAndOwnerId(video.id(), owner);
        verify(gateway).findByOwnerId(owner, request);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void rejectsMissingInputsAndDependenciesBeforeAccessingGateway() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new GetVideoUseCase(null)),
                () -> assertThrows(NullPointerException.class, () -> new ListVideosUseCase(null)),
                () -> assertThrows(NullPointerException.class, () -> getVideo.execute(null, owner)),
                () -> assertThrows(NullPointerException.class, () -> getVideo.execute(video.id(), null)),
                () -> assertThrows(NullPointerException.class, () -> listVideos.execute(null, request)),
                () -> assertThrows(NullPointerException.class, () -> listVideos.execute(owner, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> listVideos.execute(owner, new VideoPageRequest(-1, 20))));
        verifyNoInteractions(gateway);
    }
}
