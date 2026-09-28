package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import br.com.fiap.fiapx.video.core.exception.DownloadException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import java.io.InputStream;

public class S3DownloadStorage implements DownloadStorage, AutoCloseable {
    private final S3Client client;
    public S3DownloadStorage(S3Client client) { this.client = client; }
    public void close() { client.close(); }

    public Body open(DownloadArtifact artifact) {
        try {
            var stream = client.getObject(GetObjectRequest.builder()
                    .bucket(artifact.bucket()).key(artifact.objectKey()).build());
            if (!Long.valueOf(artifact.sizeBytes()).equals(stream.response().contentLength())) {
                stream.abort();
                throw new DownloadException(DownloadException.Reason.UNAVAILABLE);
            }
            return new Body() {
                public InputStream input() { return stream; }
                public void abort() { stream.abort(); }
            };
        } catch (RuntimeException failure) {
            throw new DownloadException(DownloadException.Reason.UNAVAILABLE, failure);
        }
    }
}
