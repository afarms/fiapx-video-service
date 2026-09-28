package br.com.fiap.fiapx.video.core.exception;

public final class DownloadException extends RuntimeException {
    public enum Reason { NOT_READY, EXPIRED, UNAVAILABLE }
    private final Reason reason;
    public DownloadException(Reason reason) { super(reason.name()); this.reason = reason; }
    public DownloadException(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
    public Reason reason() { return reason; }
}
