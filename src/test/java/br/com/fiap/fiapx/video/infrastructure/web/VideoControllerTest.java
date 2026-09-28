package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.domain.*;
import br.com.fiap.fiapx.video.core.gateway.*;
import br.com.fiap.fiapx.video.core.usecase.*;
import br.com.fiap.fiapx.video.core.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoControllerTest {
    final UUID owner=UUID.randomUUID();
    final AccountAccessGateway accounts=mock(AccountAccessGateway.class);
    final VideoGateway videos=mock(VideoGateway.class);
    final VideoController controller=new VideoController(new AuthorizeVideoAccessUseCase(accounts),new GetVideoUseCase(videos),new ListVideosUseCase(videos));
    final Jwt jwt=Jwt.withTokenValue("token").header("alg","RS256").subject(owner.toString()).claim("ver",2L).build();
    final Video video=new Video(UUID.randomUUID(),owner,"sample.mp4","private/key",100,Instant.now());

    @Test void queriesDeriveOwnerFromJwtAndPublicDtoExcludesStorageKey() {
        when(accounts.validate("token")).thenReturn(new AccountAccess(owner,"ADMIN",true,2L));
        when(videos.findByIdAndOwnerId(video.id(),owner)).thenReturn(Optional.of(video));
        when(videos.findByOwnerId(owner,new VideoPageRequest(0,20))).thenReturn(new VideoPage(List.of(video),0,20,1));
        var result=controller.get(jwt,video.id());
        assertEquals(video.id(),result.id()); assertEquals("sample.mp4",result.originalName());
        assertEquals(100,result.sizeBytes()); assertEquals("UPLOADING",result.status()); assertEquals(video.createdAt(),result.createdAt());
        var page=controller.list(jwt,0,20); assertEquals(List.of(result),page.items());
        assertEquals(0,page.page()); assertEquals(20,page.size()); assertEquals(1,page.totalElements());
        assertEquals(List.of("id","originalName","sizeBytes","status","createdAt","completedAt","expiresAt","failedAt","failureCode"),Arrays.stream(result.getClass().getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        verify(accounts,times(2)).validate("token");
    }

    @Test void deniedRequestsNeverQueryPersistence() {
        for(var reason:VideoAccessException.Reason.values()) {
            when(accounts.validate("token")).thenThrow(new VideoAccessException(reason));
            assertThrows(VideoAccessException.class,()->controller.get(jwt,video.id()));
            assertThrows(VideoAccessException.class,()->controller.list(jwt,0,20));
            reset(accounts);
        }
        verifyNoInteractions(videos);
    }

    @Test void invalidPaginationDoesNotQueryPersistence() {
        when(accounts.validate("token")).thenReturn(new AccountAccess(owner,"USER",true,2L));
        assertThrows(IllegalArgumentException.class,()->controller.list(jwt,-1,20));
        assertThrows(IllegalArgumentException.class,()->controller.list(jwt,0,101));
        verifyNoInteractions(videos);
    }

    @Test void errorsAreSanitizedAndBearerChallengeIsPresentOn401() {
        var errors=new VideoApiErrors(); int[] statuses={401,403,503}; int i=0;
        for(var reason:VideoAccessException.Reason.values()) {
            var response=errors.access(new VideoAccessException(reason)); assertEquals(statuses[i++],response.getStatusCode().value());
            if(reason==VideoAccessException.Reason.UNAUTHORIZED) assertEquals("Bearer",response.getHeaders().getFirst("WWW-Authenticate"));
        }
        assertEquals(404,errors.notFound(new VideoNotFoundException()).getStatusCode().value());
        assertEquals(503,errors.persistence(new VideoPersistenceException("secret database info",null)).getStatusCode().value());
        var invalid=errors.invalid(new IllegalArgumentException("private"));
        assertEquals(400,invalid.getStatusCode().value()); assertEquals("Bad Request",invalid.getBody().getDetail());
    }
}
