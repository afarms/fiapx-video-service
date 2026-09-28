package br.com.fiap.fiapx.video.core.gateway;

import java.nio.file.Path;

public interface OriginalStorageGateway {
    void put(String objectKey, Path file, String sha256);
}
