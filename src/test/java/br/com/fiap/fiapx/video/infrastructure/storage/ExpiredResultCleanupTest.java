package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import br.com.fiap.fiapx.video.core.usecase.CleanupExpiredResultsUseCase;
import br.com.fiap.fiapx.video.infrastructure.config.BeanConfig;
import br.com.fiap.fiapx.video.core.gateway.DownloadGateway;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpiredResultCleanupTest {
    @Test void exactObjectDeleteHasBoundedTimeoutAndTreatsOnlyMissingKeyAsSuccess() {
        var client=mock(S3Client.class); var video=UUID.randomUUID(); var owner=UUID.randomUUID();
        var artifact=new DownloadArtifact(video,owner,"fiapx-media-test","results/"+owner+"/"+video+"/"+UUID.randomUUID()+"/frames.zip",100,"a".repeat(64));
        try(var storage=new S3ResultCleanupStorage(client)) {
            storage.delete(artifact);
            var request=org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class); verify(client).deleteObject(request.capture());
            assertEquals(artifact.bucket(),request.getValue().bucket()); assertEquals(artifact.objectKey(),request.getValue().key());
            assertEquals(Duration.ofSeconds(15),request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow());
            when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
            assertDoesNotThrow(()->storage.delete(artifact));
            when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403).build());
            assertThrows(S3Exception.class,()->storage.delete(artifact));
        }
        verify(client).close();
    }
    @Test void scheduledFailureDoesNotStopFutureScans() {
        var usecase=mock(CleanupExpiredResultsUseCase.class); var scheduler=new ExpiredResultCleanup(usecase);
        when(usecase.execute()).thenThrow(new IllegalStateException()).thenReturn(1);
        assertDoesNotThrow(scheduler::poll); assertDoesNotThrow(scheduler::poll); verify(usecase,times(2)).execute();
    }
    @Test void composesDedicatedSchedulerAndStorageWithCleanupOnly() {
        var config=new BeanConfig(); var credentials=software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test","test"));
        try(var storage=(S3ResultCleanupStorage)config.resultCleanupStorage(credentials,"us-east-1")) {
            assertNotNull(config.expiredResultCleanup(config.cleanupExpiredResultsUseCase(mock(DownloadGateway.class),storage,120,300,10)));
        }
        var scheduler=config.resultCleanupScheduler(60000); scheduler.initialize();
        try { assertEquals(1,scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()); } finally { scheduler.shutdown(); }
        assertThrows(IllegalArgumentException.class,()->config.resultCleanupScheduler(0));
    }
}
