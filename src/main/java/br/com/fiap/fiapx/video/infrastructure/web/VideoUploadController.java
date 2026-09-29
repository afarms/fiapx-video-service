package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.exception.UploadException;
import br.com.fiap.fiapx.video.core.usecase.UploadVideoUseCase;
import br.com.fiap.fiapx.video.infrastructure.security.UploadAdmissionFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "upload.enabled", havingValue = "true")
@SecurityRequirement(name = "bearerAuth")
public class VideoUploadController {
    private final UploadVideoUseCase upload;
    private final UploadFiles files;
    public VideoUploadController(UploadVideoUseCase upload, UploadFiles files) { this.upload = upload; this.files = files; }

    @PostMapping(value = "/videos", consumes = "multipart/form-data")
    public ResponseEntity<VideoController.VideoResponse> upload(@RequestHeader("Idempotency-Key") String key,
            @RequestPart("file") MultipartFile file, HttpServletRequest request) throws java.io.IOException, jakarta.servlet.ServletException {
        UUID intention;
        try {
            intention = UUID.fromString(key);
            if (!intention.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new UploadException(UploadException.Reason.INVALID); }
        if (!(request.getAttribute(UploadAdmissionFilter.OWNER_ATTRIBUTE) instanceof UUID owner)) {
            throw new UploadException(UploadException.Reason.UNAVAILABLE);
        }
        if (!(request instanceof MultipartHttpServletRequest multipart)
                || multipart.getMultiFileMap().size() != 1 || multipart.getFiles("file").size() != 1
                || !multipart.getParameterMap().isEmpty() || request.getParts().size() != 1) {
            throw new UploadException(UploadException.Reason.INVALID);
        }
        try (var input = file.getInputStream(); var staged = files.stage(input)) {
            var video = upload.execute(owner, intention, file.getOriginalFilename(), staged.size(), staged.sha256(), staged.path());
            return ResponseEntity.accepted().location(URI.create(request.getContextPath() + "/videos/" + video.id()))
                    .body(VideoController.VideoResponse.from(video));
        }
    }
}
