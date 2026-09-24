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
