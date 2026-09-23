package br.com.fiap.fiapx.video.core.exception;

/** Persistence failure exposed without requiring a framework in the core. */
public class VideoPersistenceException extends RuntimeException {
    public VideoPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
