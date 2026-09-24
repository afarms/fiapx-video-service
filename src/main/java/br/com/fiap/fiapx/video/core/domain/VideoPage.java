package br.com.fiap.fiapx.video.core.domain;

import java.util.List;

/** Internal query result; totalElements counts only the requested owner's videos. */
public record VideoPage(List<Video> items, int page, int size, long totalElements) {
    public VideoPage {
        items = List.copyOf(items);
        new VideoPageRequest(page, size);
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must be nonnegative");
        }
    }
}
