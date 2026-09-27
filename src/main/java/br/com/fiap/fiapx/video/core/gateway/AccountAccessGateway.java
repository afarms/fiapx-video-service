package br.com.fiap.fiapx.video.core.gateway;
import br.com.fiap.fiapx.video.core.domain.AccountAccess;
public interface AccountAccessGateway {
    AccountAccess validate(String token);
}
