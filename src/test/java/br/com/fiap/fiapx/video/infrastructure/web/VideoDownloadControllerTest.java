package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.usecase.DownloadVideoUseCase;
import br.com.fiap.fiapx.video.core.gateway.DownloadGateway;
import br.com.fiap.fiapx.video.core.exception.DownloadException;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoDownloadControllerTest {
    DownloadVideoUseCase usecase=mock(DownloadVideoUseCase.class);
    DownloadGateway gateway=mock(DownloadGateway.class);
    DownloadTransfers transfers=mock(DownloadTransfers.class);
    VideoDownloadController controller=new VideoDownloadController(usecase,gateway,transfers);
    HttpServletRequest request=mock(HttpServletRequest.class);
    HttpServletResponse response=mock(HttpServletResponse.class);
    UUID video=UUID.randomUUID(),owner=UUID.randomUUID();
    Jwt jwt=Jwt.withTokenValue("token").header("alg","RS256").subject(owner.toString()).claim("ver",1L).build();
    @Test void handsAdmittedReservationToTransport() {
        var lease=mock(DownloadGateway.Lease.class);
        when(usecase.execute(video,"token",owner,1)).thenReturn(lease);
        controller.download(video,jwt,request,response);
        verify(transfers).start(eq(request),eq(response),eq(lease),anyLong()); verify(transfers,never()).cancelAdmission();
    }
    @Test void rejectedAdmissionReturnsCapacity() {
        when(usecase.execute(any(),any(),any(),anyLong())).thenThrow(new DownloadException(DownloadException.Reason.EXPIRED));
        assertThrows(DownloadException.class,()->controller.download(video,jwt,request,response));
        verify(transfers).cancelAdmission(); verifyNoInteractions(gateway);
    }
    @Test void failedAsyncSetupReleasesDurableReservationEvenIfReleaseFails() {
        var token=UUID.randomUUID(); var lease=new DownloadGateway.Lease(token,null,null,null);
        when(usecase.execute(video,"token",owner,1)).thenReturn(lease);
        doThrow(new IllegalStateException()).when(transfers).start(any(),any(),any(),anyLong());
        doThrow(new IllegalStateException()).when(gateway).release(video,token);
        assertThrows(IllegalStateException.class,()->controller.download(video,jwt,request,response));
        verify(transfers).cancelAdmission(); verify(gateway).release(video,token);
    }
    @Test void dependencyErrorsNeverExposeCause() {
        var errors=new VideoApiErrors(); int[] statuses={409,410,503}; int i=0;
        for(var reason:DownloadException.Reason.values()) {
            var response=errors.download(new DownloadException(reason,new IllegalStateException("private/key")));
            assertEquals(statuses[i++],response.getStatusCode().value());
            assertFalse(response.getBody().toString().contains("private/key"));
            assertEquals("no-store",response.getHeaders().getFirst("Cache-Control"));
        }
    }
}
