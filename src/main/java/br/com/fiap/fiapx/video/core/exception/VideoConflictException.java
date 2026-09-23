package br.com.fiap.fiapx.video.core.exception;

public class VideoConflictException extends VideoPersistenceException {
    public VideoConflictException(Throwable cause) {
        super("Video metadata conflicts with a persistence constraint", cause);
    }
}
