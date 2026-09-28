package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import br.com.fiap.fiapx.video.core.gateway.ResultCleanupStorageGateway;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import java.time.Duration;

public class S3ResultCleanupStorage implements ResultCleanupStorageGateway, AutoCloseable {
    private final S3Client client;
    public S3ResultCleanupStorage(S3Client client) { this.client = client; }
    public void delete(DownloadArtifact artifact) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(artifact.bucket()).key(artifact.objectKey())
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))).build());
        } catch (NoSuchKeyException absent) {
            // Missing key is idempotent success; a missing bucket or denied access is not.
        }
    }
    public void close() { client.close(); }
}
