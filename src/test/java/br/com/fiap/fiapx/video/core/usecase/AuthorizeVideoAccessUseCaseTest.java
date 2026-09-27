package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.domain.AccountAccess;
import br.com.fiap.fiapx.video.core.gateway.AccountAccessGateway;
import br.com.fiap.fiapx.video.core.exception.VideoAccessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static br.com.fiap.fiapx.video.core.exception.VideoAccessException.Reason.*;

class AuthorizeVideoAccessUseCaseTest {
    final UUID id = UUID.randomUUID();
    final AccountAccessGateway gateway = mock(AccountAccessGateway.class);
    final AuthorizeVideoAccessUseCase service = new AuthorizeVideoAccessUseCase(gateway);

    @Test void userAndAdminAreBoundToTheirOwnSubjectAndEveryCallChecksCurrentAccount() {
        when(gateway.validate("token")).thenReturn(new AccountAccess(id,"USER",true,2L),
                new AccountAccess(id,"ADMIN",true,2L)).thenThrow(new VideoAccessException(UNAUTHORIZED));
        assertEquals(id,service.execute("token",id,2));
        assertEquals(id,service.execute("token",id,2));
        assertEquals(UNAUTHORIZED,assertThrows(VideoAccessException.class,()->service.execute("token",id,2)).reason());
        verify(gateway,times(3)).validate("token");
    }

    @Test void invalidLocalInputsNeverCallIdentity() {
        assertThrows(NullPointerException.class,()->new AuthorizeVideoAccessUseCase(null));
        for (String token : Arrays.asList(null,""," "))
            assertEquals(UNAUTHORIZED,assertThrows(VideoAccessException.class,()->service.execute(token,id,2)).reason());
        assertEquals(UNAUTHORIZED,assertThrows(VideoAccessException.class,()->service.execute("token",null,2)).reason());
        assertEquals(UNAUTHORIZED,assertThrows(VideoAccessException.class,()->service.execute("token",id,-1)).reason());
        verifyNoInteractions(gateway);
    }

    @Test void inconsistentResponsesFailClosedAndBlockedAccountIsForbidden() {
        var cases = Arrays.asList(null,new AccountAccess(null,"USER",true,2L),
                new AccountAccess(UUID.randomUUID(),"USER",true,2L),new AccountAccess(id,"USER",true,null),
                new AccountAccess(id,"USER",true,3L),new AccountAccess(id,"USER",null,2L),
                new AccountAccess(id,"OTHER",true,2L),new AccountAccess(id,null,true,2L));
        for (var account : cases) {
            when(gateway.validate("token")).thenReturn(account);
            assertEquals(UNAVAILABLE,assertThrows(VideoAccessException.class,()->service.execute("token",id,2)).reason());
        }
        when(gateway.validate("token")).thenReturn(new AccountAccess(id,"USER",false,2L));
        assertEquals(FORBIDDEN,assertThrows(VideoAccessException.class,()->service.execute("token",id,2)).reason());
    }
}
