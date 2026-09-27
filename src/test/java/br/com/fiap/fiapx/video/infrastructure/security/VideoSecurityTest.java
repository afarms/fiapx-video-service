package br.com.fiap.fiapx.video.infrastructure.security;

import br.com.fiap.fiapx.video.infrastructure.config.BeanConfig;
import br.com.fiap.fiapx.video.core.gateway.AccountAccessGateway;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import java.security.*;
import java.security.interfaces.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoSecurityTest {
    static KeyPair pair;
    final BeanConfig config=new BeanConfig();
    final Instant now=Instant.now();
    @BeforeAll static void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair=generator.generateKeyPair();
    }
    JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder().issuer("issuer").audience(List.of("audience")).subject(UUID.randomUUID().toString())
            .issuedAt(now).expiresAt(now.plusSeconds(1800)).claim("ver",2L);
    }
    String sign(JwtClaimsSet claims) {
        var key=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey((RSAPrivateKey)pair.getPrivate()).build();
        var encoder=new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    @Test void verifiesSignatureIssuerAudienceExpiryAndContractClaims() {
        var decoder=config.jwtDecoder((RSAPublicKey)pair.getPublic(),config.clock(),"issuer","audience");
        assertEquals(2L,((Number)decoder.decode(sign(claims().build())).getClaim("ver")).longValue());
        var cases=List.of(claims().issuer("other").build(),claims().audience(List.of("other")).build(),
            claims().issuedAt(now.minusSeconds(2000)).expiresAt(now.minusSeconds(1)).build(),
            claims().subject("invalid").build(),claims().claim("ver",-1L).build(),claims().claim("ver","2").build(),
            claims().claim("ver",2.5).build(),claims().expiresAt(now.plusSeconds(1801)).build(),
            claims().issuedAt(now.plusSeconds(60)).build(),claims().claims(c->c.remove("ver")).build(),
            claims().claims(c->c.remove("exp")).build(),claims().claims(c->c.remove("iat")).build(),
            claims().claims(c->c.remove("aud")).build());
        for(var candidate:cases) assertThrows(JwtException.class,()->decoder.decode(sign(candidate)));
        var parts=sign(claims().build()).split("\\.");
        assertThrows(JwtException.class,()->decoder.decode(parts[0]+"."+parts[1]+".AAAA"));
        assertThrows(JwtException.class,()->decoder.decode("invalid"));
    }
    @Test void validatorAcceptsIntegerVersionAndRejectsInvertedDates() {
        var validator=new VideoJwtValidator("audience",Clock.fixed(now,ZoneOffset.UTC));
        var jwt=Jwt.withTokenValue("t").header("alg","RS256").subject(UUID.randomUUID().toString())
            .audience(List.of("audience")).issuedAt(now).expiresAt(now.plusSeconds(1800)).claim("ver",2).build();
        assertFalse(validator.validate(jwt).hasErrors());
        var inverted=mock(Jwt.class);
        when(inverted.getSubject()).thenReturn(UUID.randomUUID().toString()); when(inverted.getClaim("ver")).thenReturn(2L);
        when(inverted.getAudience()).thenReturn(List.of("audience"));
        when(inverted.getIssuedAt()).thenReturn(now); when(inverted.getExpiresAt()).thenReturn(now);
        assertTrue(validator.validate(inverted).hasErrors());
    }
    @Test void readsPublicKeyAndConstructsCentralizedDependenciesWithConfigurationValidation() throws Exception {
        String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----";
        assertEquals(pair.getPublic(),config.publicKey(new ByteArrayResource(pem.getBytes())));
        assertEquals("bearer",config.openApi().getComponents().getSecuritySchemes().get("bearerAuth").getScheme());
        assertNotNull(config.authorizeVideoAccessUseCase(mock(AccountAccessGateway.class)));
        for(String url:List.of("http://localhost:8081","https://identity"))
            assertNotNull(config.accountAccessGateway(config.identityClient(URI.create(url),"x".repeat(32),2000,3000)));
        for(String url:List.of("ftp://identity","http:/missing-host","http://user@identity","http://identity?x=1","http://identity#fragment"))
            assertThrows(IllegalArgumentException.class,()->config.identityClient(URI.create(url),"x".repeat(32),2000,3000));
        for(String key:List.of(""," ","short"))
            assertThrows(IllegalArgumentException.class,()->config.identityClient(URI.create("http://identity"),key,2000,3000));
        assertThrows(IllegalArgumentException.class,()->config.identityClient(URI.create("http://identity"),"x".repeat(32),0,3000));
        assertThrows(IllegalArgumentException.class,()->config.identityClient(URI.create("http://identity"),"x".repeat(32),2000,0));
    }
    @Configuration(proxyBeanMethods=false)
    @EnableWebSecurity
    static class SecurityTestConfig {
        @Bean JwtDecoder decoder() { return mock(JwtDecoder.class); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception { return new BeanConfig().securityFilterChain(http); }
    }
    @Test void createsStatelessBearerFilterChain() {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(SecurityTestConfig.class); context.refresh();
            var chain=context.getBean(SecurityFilterChain.class);
            assertTrue(chain.getFilters().stream().anyMatch(f->f.getClass().getSimpleName().equals("BearerTokenAuthenticationFilter")));
            assertFalse(chain.getFilters().stream().anyMatch(f->f.getClass().getSimpleName().equals("CsrfFilter")));
        }
    }
}
