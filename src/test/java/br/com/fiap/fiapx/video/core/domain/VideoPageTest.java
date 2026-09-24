package br.com.fiap.fiapx.video.core.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VideoPageTest {
    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101"})
    void rejectsInvalidPagination(int page, int size) {
        assertThrows(IllegalArgumentException.class, () -> new VideoPageRequest(page, size));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "0,100", "2147483647,100"})
    void acceptsPaginationBoundaries(int page, int size) {
        var request = new VideoPageRequest(page, size);
        assertEquals(page, request.page());
        assertEquals(size, request.size());
    }

    @Test
    void makesDefensiveImmutableCopyWhilePreservingPageMetadata() {
        var video = new Video(UUID.randomUUID(), UUID.randomUUID(), "sample.mp4", "key", 1, Instant.now());
        var source = new ArrayList<>(List.of(video));
        var page = new VideoPage(source, 2, 1, 10);
        source.clear();
        assertEquals(List.of(video), page.items());
        assertEquals(2, page.page());
        assertEquals(1, page.size());
        assertEquals(10, page.totalElements());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }

    @Test
    void allowsEmptyPagesAndRejectsInvalidResultMetadata() {
        assertEquals(0, new VideoPage(List.of(), 0, 20, 0).totalElements());
        assertEquals(5, new VideoPage(List.of(), 9, 20, 5).totalElements());
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new VideoPage(List.of(), 0, 20, -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new VideoPage(List.of(), -1, 20, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new VideoPage(List.of(), 0, 0, 0)),
                () -> assertThrows(NullPointerException.class, () -> new VideoPage(null, 0, 20, 0)));
    }
}
