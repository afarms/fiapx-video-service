package br.com.fiap.fiapx.video.infrastructure.config;

import br.com.fiap.fiapx.video.core.gateway.VideoGateway;
import br.com.fiap.fiapx.video.core.usecase.GetVideoUseCase;
import br.com.fiap.fiapx.video.core.usecase.ListVideosUseCase;
import br.com.fiap.fiapx.video.infrastructure.persistence.adapter.VideoGatewayAdapter;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.VideoMapper;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import br.com.fiap.fiapx.video.core.gateway.AccountAccessGateway;
import br.com.fiap.fiapx.video.core.usecase.AuthorizeVideoAccessUseCase;
import br.com.fiap.fiapx.video.infrastructure.identity.IdentityHttpAdapter;
import br.com.fiap.fiapx.video.infrastructure.security.VideoJwtValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.interfaces.RSAPublicKey;
import java.time.*;

@Configuration(proxyBeanMethods = false)
public class BeanConfig {
    @Bean
    public Clock clock() { return Clock.systemUTC(); }

    @Bean
    public AuthorizeVideoAccessUseCase authorizeVideoAccessUseCase(AccountAccessGateway gateway) {
        return new AuthorizeVideoAccessUseCase(gateway);
    }

    @Bean
    public RestClient identityClient(@Value("${identity.url}") URI url,
            @Value("${identity.service-key}") String key,
            @Value("${identity.connect-timeout-ms:2000}") int connectTimeout,
            @Value("${identity.read-timeout-ms:3000}") int readTimeout) {
        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null
                || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("identity.url must be an HTTP(S) URL without credentials, query or fragment");
        }
        if (key.isBlank() || key.length() < 32 || connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalArgumentException("Identity credential and timeouts are required");
        }
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        return RestClient.builder().baseUrl(url.toString()).defaultHeader("X-Service-Key", key)
                .requestFactory(factory).build();
    }

    @Bean
    public AccountAccessGateway accountAccessGateway(RestClient identityClient) {
        return new IdentityHttpAdapter(identityClient);
    }

    @Bean
    public RSAPublicKey publicKey(@Value("${identity.jwt.public-key}") Resource file) throws Exception {
        try (var input = file.getInputStream()) { return RsaKeyConverters.x509().convert(input); }
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey key, Clock clock, @Value("${identity.jwt.issuer}") String issuer,
            @Value("${identity.jwt.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withPublicKey(key).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer), new VideoJwtValidator(audience, clock)));
        return decoder;
    }

    @Bean
    public io.swagger.v3.oas.models.OpenAPI openApi() {
        return new io.swagger.v3.oas.models.OpenAPI().components(new io.swagger.v3.oas.models.Components()
                .addSecuritySchemes("bearerAuth", new io.swagger.v3.oas.models.security.SecurityScheme()
                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/swagger-ui.html",
                        "/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> {})).build();
    }

    @Bean
    public GetVideoUseCase getVideoUseCase(VideoGateway gateway) {
        return new GetVideoUseCase(gateway);
    }

    @Bean
    public ListVideosUseCase listVideosUseCase(VideoGateway gateway) {
        return new ListVideosUseCase(gateway);
    }

    @Bean
    public VideoMapper videoMapper() {
        return new VideoMapper();
    }

    @Bean
    public VideoGateway videoGateway(SpringVideoRepository repository, VideoMapper mapper) {
        return new VideoGatewayAdapter(repository, mapper);
    }
}
