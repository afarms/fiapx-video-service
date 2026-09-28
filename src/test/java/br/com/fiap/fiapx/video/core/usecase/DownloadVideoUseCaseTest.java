package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.domain.AccountAccess;
import br.com.fiap.fiapx.video.core.exception.VideoAccessException;
import br.com.fiap.fiapx.video.core.gateway.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadVideoUseCaseTest {
    AccountAccessGateway accounts=mock(AccountAccessGateway.class);
    DownloadGateway gateway=mock(DownloadGateway.class);
    UUID owner=UUID.randomUUID(), video=UUID.randomUUID();
    DownloadVideoUseCase usecase=new DownloadVideoUseCase(new AuthorizeVideoAccessUseCase(accounts),gateway,
            Duration.ofSeconds(120),Duration.ofSeconds(1800));
    @Test void validatesCurrentIdentityOnEveryNewRequestIncludingAdmin() {
        when(accounts.validate("token")).thenReturn(new AccountAccess(owner,"ADMIN",true,1L));
        usecase.execute(video,"token",owner,1); usecase.execute(video,"token",owner,1);
        var order=inOrder(accounts,gateway);
        order.verify(accounts).validate("token"); order.verify(gateway).reserve(video,owner,Duration.ofSeconds(120),Duration.ofSeconds(1800));
        order.verify(accounts).validate("token"); order.verify(gateway).reserve(video,owner,Duration.ofSeconds(120),Duration.ofSeconds(1800));
    }
    @Test void inactiveOrUnavailableIdentityNeverAcquiresReservation() {
        when(accounts.validate("token")).thenReturn(new AccountAccess(owner,"USER",false,1L));
        assertThrows(VideoAccessException.class,()->usecase.execute(video,"token",owner,1));
        when(accounts.validate("token")).thenThrow(new VideoAccessException(VideoAccessException.Reason.UNAVAILABLE));
        assertThrows(VideoAccessException.class,()->usecase.execute(video,"token",owner,1)); verifyNoInteractions(gateway);
    }
}
