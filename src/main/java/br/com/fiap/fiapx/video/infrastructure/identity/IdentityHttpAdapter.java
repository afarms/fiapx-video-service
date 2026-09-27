package br.com.fiap.fiapx.video.infrastructure.identity;
import br.com.fiap.fiapx.video.core.domain.AccountAccess;
import br.com.fiap.fiapx.video.core.gateway.AccountAccessGateway;
import br.com.fiap.fiapx.video.core.exception.VideoAccessException;
import static br.com.fiap.fiapx.video.core.exception.VideoAccessException.Reason.*;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.*;
import java.util.Map;
import java.util.Objects;
public class IdentityHttpAdapter implements AccountAccessGateway {
    private final RestClient client;
    public IdentityHttpAdapter(RestClient client) { this.client = Objects.requireNonNull(client); }
    @Override
    public AccountAccess validate(String token) {
        try {
            var response = client.post().uri("/internal/accounts/validate")
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("token", token))
                    .retrieve().toEntity(AccountAccess.class);
            if (response.getStatusCode().value() != 200 || response.getBody() == null) {
                throw new VideoAccessException(UNAVAILABLE);
            }
            return response.getBody();
        } catch (RestClientResponseException e) {
            String code = errorCode(e);
            if (e.getStatusCode().value() == 401 && "UNAUTHORIZED".equals(code)) {
                throw new VideoAccessException(UNAUTHORIZED);
            }
            if (e.getStatusCode().value() == 403 && "FORBIDDEN".equals(code)) {
                throw new VideoAccessException(FORBIDDEN);
            }
            throw new VideoAccessException(UNAVAILABLE);
        } catch (RestClientException e) {
            // Do not retain transport exceptions containing request bodies or tokens.
            throw new VideoAccessException(UNAVAILABLE);
        }
    }
    private String errorCode(RestClientResponseException exception) {
        try {
            var body = exception.getResponseBodyAs(ProblemDetail.class);
            if (body == null || body.getProperties() == null) return null;
            var code = body.getProperties().get("code");
            return code instanceof String text ? text : null;
        } catch (RuntimeException e) { return null; }
    }
}
