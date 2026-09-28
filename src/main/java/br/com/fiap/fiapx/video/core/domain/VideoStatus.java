package br.com.fiap.fiapx.video.core.domain;

public enum VideoStatus {
    UPLOADING, QUEUED, PROCESSING, COMPLETED, FAILED;

    public boolean terminal() { return this == COMPLETED || this == FAILED; }
}
