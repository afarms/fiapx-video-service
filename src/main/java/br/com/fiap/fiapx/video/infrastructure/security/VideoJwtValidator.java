package br.com.fiap.fiapx.video.infrastructure.security;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.*;
import java.util.UUID;
public class VideoJwtValidator implements OAuth2TokenValidator<Jwt> {
    private final String audience;
    private final Clock clock;
    public VideoJwtValidator(String audience, Clock clock) { this.audience = audience; this.clock = clock; }
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
            var version = jwt.getClaim("ver");
            if (!(version instanceof Long || version instanceof Integer) || ((Number) version).longValue() < 0
                    || jwt.getExpiresAt() == null || jwt.getIssuedAt() == null || !jwt.getAudience().contains(audience)
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds() > 1800
                    || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))) {
                throw new IllegalArgumentException();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }
    }
}
