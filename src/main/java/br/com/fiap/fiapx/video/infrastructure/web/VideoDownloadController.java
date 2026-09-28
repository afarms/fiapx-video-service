package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.usecase.DownloadVideoUseCase;
import br.com.fiap.fiapx.video.core.gateway.DownloadGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "download.enabled", havingValue = "true")
@SecurityRequirement(name = "bearerAuth")
public class VideoDownloadController {
    private final DownloadVideoUseCase download;
    private final DownloadGateway gateway;
    private final DownloadTransfers transfers;

    public VideoDownloadController(DownloadVideoUseCase download, DownloadGateway gateway, DownloadTransfers transfers) {
        this.download = download; this.gateway = gateway; this.transfers = transfers;
    }

    @GetMapping("/videos/{id}/download")
    @Operation(summary = "Baixar o ZIP completo de um vídeo próprio; sem retomada parcial")
    @ApiResponse(responseCode = "200", description = "ZIP completo",
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "Vídeo inexistente ou de outro proprietário", content = @Content)
    @ApiResponse(responseCode = "409", description = "Vídeo não concluído", content = @Content)
    @ApiResponse(responseCode = "410", description = "Resultado expirado", content = @Content)
    @ApiResponse(responseCode = "503", description = "Dependência indisponível ou capacidade esgotada", content = @Content)
    public void download(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                         HttpServletRequest request, HttpServletResponse response) {
        transfers.acquire();
        DownloadGateway.Lease lease = null;
        try {
            long started = System.nanoTime();
            lease = download.execute(id, jwt.getTokenValue(), UUID.fromString(jwt.getSubject()),
                    ((Number) jwt.getClaim("ver")).longValue());
            transfers.start(request, response, lease, started);
        } catch (RuntimeException failure) {
            transfers.cancelAdmission();
            if (lease != null) {
                try { gateway.release(id, lease.token()); } catch (RuntimeException ignored) { }
            }
            throw failure;
        }
    }
}
