package br.com.fiap.fiapx.video.infrastructure.config;

import br.com.fiap.fiapx.video.core.gateway.VideoGateway;
import br.com.fiap.fiapx.video.core.usecase.GetVideoUseCase;
import br.com.fiap.fiapx.video.core.usecase.ListVideosUseCase;
import br.com.fiap.fiapx.video.infrastructure.persistence.adapter.VideoGatewayAdapter;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BeanConfig {
    @Bean
    public GetVideoUseCase getVideoUseCase(VideoGateway gateway) {
        return new GetVideoUseCase(gateway);
    }

    @Bean
    public ListVideosUseCase listVideosUseCase(VideoGateway gateway) {
        return new ListVideosUseCase(gateway);
    }

    @Bean
    public VideoMapper videoMapper() {
        return new VideoMapper();
    }

    @Bean
    public VideoGateway videoGateway(SpringVideoRepository repository, VideoMapper mapper) {
        return new VideoGatewayAdapter(repository, mapper);
    }
}
