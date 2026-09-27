package br.com.fiap.fiapx.video.infrastructure.web;
import br.com.fiap.fiapx.video.core.domain.*;
import br.com.fiap.fiapx.video.core.usecase.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.*;
@RestController
@RequestMapping("/videos")
@SecurityRequirement(name = "bearerAuth")
public class VideoController {
    private final AuthorizeVideoAccessUseCase authorize;
    private final GetVideoUseCase getVideo;
    private final ListVideosUseCase listVideos;
    public VideoController(AuthorizeVideoAccessUseCase authorize, GetVideoUseCase getVideo, ListVideosUseCase listVideos) {
        this.authorize = authorize;
        this.getVideo = getVideo;
        this.listVideos = listVideos;
    }
    public record VideoResponse(UUID id, String originalName, long sizeBytes, String status, Instant createdAt) {
        static VideoResponse from(Video video) {
            return new VideoResponse(video.id(), video.originalName(), video.sizeBytes(), video.status(), video.createdAt());
        }
    }
    public record PageResponse(List<VideoResponse> items, int page, int size, long totalElements) {}
    private UUID owner(Jwt jwt) {
        return authorize.execute(jwt.getTokenValue(), UUID.fromString(jwt.getSubject()),
                ((Number) jwt.getClaim("ver")).longValue());
    }
    @GetMapping("/{id}")
    public VideoResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return VideoResponse.from(getVideo.execute(id, owner(jwt)));
    }
    @GetMapping
    public PageResponse list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var owner = owner(jwt);
        var result = listVideos.execute(owner, new VideoPageRequest(page, size));
        return new PageResponse(result.items().stream().map(VideoResponse::from).toList(),
                result.page(), result.size(), result.totalElements());
    }
}
