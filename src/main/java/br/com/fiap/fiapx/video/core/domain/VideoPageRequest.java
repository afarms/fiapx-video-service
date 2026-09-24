package br.com.fiap.fiapx.video.core.domain;

/** Zero-based pagination, independent of the persistence framework. */
public record VideoPageRequest(int page, int size) {
    public VideoPageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be nonnegative");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }
}
