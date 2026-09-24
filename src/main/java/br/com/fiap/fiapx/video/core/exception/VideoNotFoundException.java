package br.com.fiap.fiapx.video.core.exception;

/** Same outcome for an absent video and a video owned by another user. */
public class VideoNotFoundException extends RuntimeException {
    public VideoNotFoundException() {
        super("Video not found");
    }
}
