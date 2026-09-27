package br.com.fiap.fiapx.video.core.exception;
public class VideoAccessException extends RuntimeException {
    public enum Reason { UNAUTHORIZED, FORBIDDEN, UNAVAILABLE }
    private final Reason reason;
    public VideoAccessException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
