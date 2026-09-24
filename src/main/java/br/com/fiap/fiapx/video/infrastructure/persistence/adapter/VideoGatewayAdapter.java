package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.domain.VideoPage;
import br.com.fiap.fiapx.video.core.domain.VideoPageRequest;
import br.com.fiap.fiapx.video.core.exception.VideoConflictException;
import br.com.fiap.fiapx.video.core.exception.VideoPersistenceException;
import br.com.fiap.fiapx.video.core.gateway.VideoGateway;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public class VideoGatewayAdapter implements VideoGateway {
    private final SpringVideoRepository repository;
    private final VideoMapper mapper;

    public VideoGatewayAdapter(SpringVideoRepository repository, VideoMapper mapper) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public void insert(Video video) {
        var entity = mapper.toEntity(Objects.requireNonNull(video, "video is required"));
        try {
            // Persistable.isNew preserves INSERT for assigned UUIDs; flush exposes constraint failures here.
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new VideoConflictException(exception);
        } catch (DataAccessException exception) {
            throw new VideoPersistenceException("Could not persist video metadata", exception);
        }
    }

    @Override
    public VideoPage findByOwnerId(UUID ownerId, VideoPageRequest request) {
        Objects.requireNonNull(ownerId, "ownerId is required");
        Objects.requireNonNull(request, "request is required");
        var pageable = PageRequest.of(request.page(), request.size(),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        try {
            var page = repository.findByOwnerId(ownerId, pageable);
            return new VideoPage(page.getContent().stream().map(mapper::toDomain).toList(),
                    request.page(), request.size(), page.getTotalElements());
        } catch (DataAccessException exception) {
            throw new VideoPersistenceException("Could not list video metadata", exception);
        }
    }

    @Override
    public Optional<Video> findByIdAndOwnerId(UUID id, UUID ownerId) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(ownerId, "ownerId is required");
        try {
            return repository.findByIdAndOwnerId(id, ownerId).map(mapper::toDomain);
        } catch (DataAccessException exception) {
            throw new VideoPersistenceException("Could not retrieve video metadata", exception);
        }
    }
}
