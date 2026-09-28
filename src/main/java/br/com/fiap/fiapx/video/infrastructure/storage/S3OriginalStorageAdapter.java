package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.core.exception.UploadException;
import br.com.fiap.fiapx.video.core.gateway.OriginalStorageGateway;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public class S3OriginalStorageAdapter implements OriginalStorageGateway {
    private final S3Client client;
    private final String bucket;
    public S3OriginalStorageAdapter(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client);
        this.bucket = Objects.requireNonNull(bucket);
    }
    public void put(String objectKey, Path file, String sha256) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey)
                    .contentType("application/octet-stream").ifNoneMatch("*")
                    .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256))).build(),
                    RequestBody.fromFile(file));
        } catch (RuntimeException failure) {
            // An uncertain PUT remains tied to its persisted attempt and will be reconciled later.
            throw new UploadException(UploadException.Reason.UNAVAILABLE, failure);
        }
    }
}
