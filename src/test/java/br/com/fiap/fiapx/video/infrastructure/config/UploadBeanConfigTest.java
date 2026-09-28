package br.com.fiap.fiapx.video.infrastructure.config;

import br.com.fiap.fiapx.video.core.gateway.*;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadBeanConfigTest {
    @TempDir Path directory;
    @Test void composesAdaptersAndUsesBoundedTimeoutsWithoutResolvingCredentials() throws Exception {
        var config = new BeanConfig();
        var tx = config.uploadTransactions(mock(PlatformTransactionManager.class));
        assertEquals(180, tx.getTimeout());
        var repository = mock(SpringVideoRepository.class);
        var gateway = config.uploadGateway(repository, config.videoMapper(), tx, JsonMapper.builder().build());
        var s3 = mock(S3Client.class); var sqs = mock(SqsClient.class);
        var storage = config.originalStorage(s3, "media");
        var files = config.uploadFiles(directory, 2, 500_000_000, Clock.systemUTC());
        var spool = config.multipartSpool(files);
        var multipart = config.uploadMultipartConfig(spool);
        assertEquals(spool.directory().toString(), multipart.getLocation());
        spool.close();
        assertEquals(100_000_000, multipart.getMaxFileSize());
        assertEquals(101_000_000, multipart.getMaxRequestSize());
        assertEquals(0, multipart.getFileSizeThreshold());
        assertNotNull(config.uploadVideoUseCase(gateway, storage, "media", 300));
        assertThrows(IllegalArgumentException.class, () -> config.uploadVideoUseCase(gateway, storage, "media", 120));
        assertNotNull(config.outboxDispatcher(mock(SpringOutboxRepository.class), tx, sqs,
                "https://sqs.us-east-1.amazonaws.com/000000000000/work"));
        assertNotNull(config.uploadReconciler(repository, tx, s3, "media", Clock.systemUTC(), files));
        try (var credentials = (ProfileCredentialsProvider) config.uploadCredentials("fiapx-video-local")) {
            assertNotNull(credentials);
        }
        try (var credentials = (DefaultCredentialsProvider) config.uploadCredentials("")) {
            assertNotNull(credentials);
        }
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("unit-test", "unit-test"));
        try (var client = config.uploadS3(credentials, "us-east-1")) {
            assertEquals(120, client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout().orElseThrow().toSeconds());
        }
        try (var client = config.uploadSqs(credentials, "us-east-1")) {
            assertEquals(20, client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout().orElseThrow().toSeconds());
        }
        verifyNoInteractions(s3, sqs);
    }
}
