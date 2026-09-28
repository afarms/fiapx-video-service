package br.com.fiap.fiapx.video.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.domain.VideoPageRequest;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class BeanConfigTest {
    @Test void downloadCanBeComposedWithoutUploadOrMessaging() throws Exception {
        var config=new BeanConfig();
        var gateway=mock(br.com.fiap.fiapx.video.core.gateway.DownloadGateway.class);
        var authorize=mock(br.com.fiap.fiapx.video.core.usecase.AuthorizeVideoAccessUseCase.class);
        assertNotNull(config.downloadVideoUseCase(authorize,gateway,120,1800));
        var credentials=software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test","test"));
        try(var storage=(br.com.fiap.fiapx.video.infrastructure.storage.S3DownloadStorage)config.downloadStorage(credentials,"us-east-1");
            var transfers=config.downloadTransfers(gateway,storage,2,120,1800,30)) {
            assertNotNull(transfers);
        }
        verifyNoInteractions(gateway);
    }
    @Test void downloadReservationsUseTheirOwnTransactionBoundary() {
        var config=new BeanConfig();
        assertNotNull(config.downloadGateway(mock(br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringDownloadRepository.class),
                config.downloadMapper(),mock(org.springframework.transaction.PlatformTransactionManager.class)));
    }
    @Test void composesResultGatewayAndConsumerWithDedicatedScheduling() {
        var config=new BeanConfig();
        var gateway=config.processingResultsGateway(mock(SpringVideoRepository.class),config.videoMapper(),
                mock(org.springframework.transaction.PlatformTransactionManager.class),tools.jackson.databind.json.JsonMapper.builder().build());
        var sqs=mock(software.amazon.awssdk.services.sqs.SqsClient.class);
        try (var consumer=config.processingResultsConsumer(sqs,gateway,tools.jackson.databind.json.JsonMapper.builder().build(),
                "https://sqs.us-east-1.amazonaws.com/123456789012/results","fiapx-media-test")) { assertNotNull(consumer); }
        verifyNoInteractions(sqs);
        var scheduler=config.taskScheduler(); scheduler.initialize();
        try { assertEquals(3,scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()); } finally { scheduler.shutdown(); }
    }
    @Test
    void composesBothQueriesThroughTheConfiguredGatewayAndMapper() {
        var config = new BeanConfig();
        var repository = mock(SpringVideoRepository.class);
        var mapper = config.videoMapper();
        var gateway = config.videoGateway(repository, mapper);
        var video = new Video(UUID.randomUUID(), UUID.randomUUID(), "sample.mp4", "key", 1, Instant.now());
        var entity = mapper.toEntity(video);
        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(repository.findByIdAndOwnerId(video.id(), video.ownerId())).thenReturn(Optional.of(entity));
        when(repository.findByOwnerId(video.ownerId(), pageable))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));
        assertEquals(video, config.getVideoUseCase(gateway).execute(video.id(), video.ownerId()));
        assertEquals(List.of(video), config.listVideosUseCase(gateway)
                .execute(video.ownerId(), new VideoPageRequest(0, 20)).items());
    }
}
