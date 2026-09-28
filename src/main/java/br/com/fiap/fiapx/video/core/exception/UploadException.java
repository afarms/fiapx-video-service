package br.com.fiap.fiapx.video.core.exception;

public class UploadException extends RuntimeException {
    public enum Reason { INVALID, TOO_LARGE, CONTENT_CONFLICT, IN_PROGRESS, UNAVAILABLE }
    private final Reason reason;
    public UploadException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
    public UploadException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
