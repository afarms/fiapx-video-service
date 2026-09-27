package br.com.fiap.fiapx.video.core.usecase;
import br.com.fiap.fiapx.video.core.gateway.AccountAccessGateway;
import br.com.fiap.fiapx.video.core.exception.VideoAccessException;
import static br.com.fiap.fiapx.video.core.exception.VideoAccessException.Reason.*;
import java.util.Objects;
import java.util.UUID;
public class AuthorizeVideoAccessUseCase {
    private final AccountAccessGateway gateway;
    public AuthorizeVideoAccessUseCase(AccountAccessGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway);
    }
    /** Called only after local signature and claim validation. No positive authorization cache. */
    public UUID execute(String token, UUID subject, long credentialVersion) {
        if (token == null || token.isBlank() || subject == null || credentialVersion < 0) {
            throw new VideoAccessException(UNAUTHORIZED);
        }
        var account = gateway.validate(token);
        if (account == null || !subject.equals(account.id()) || account.credentialVersion() == null
                || account.credentialVersion() != credentialVersion || account.active() == null
                || !("USER".equals(account.role()) || "ADMIN".equals(account.role()))) {
            throw new VideoAccessException(UNAVAILABLE);
        }
        if (!account.active()) throw new VideoAccessException(FORBIDDEN);
        return subject;
    }
}
