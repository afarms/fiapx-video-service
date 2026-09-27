package br.com.fiap.fiapx.video.infrastructure.identity;

import br.com.fiap.fiapx.video.core.exception.VideoAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static br.com.fiap.fiapx.video.core.exception.VideoAccessException.Reason.*;

class IdentityHttpAdapterTest {
    final RestClient.Builder builder = RestClient.builder().baseUrl("http://identity").defaultHeader("X-Service-Key","service-key");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final IdentityHttpAdapter adapter = new IdentityHttpAdapter(builder.build());

    @Test void sendsServiceCredentialAndTokenAndReadsCurrentAccount() {
        server.expect(requestTo("http://identity/internal/accounts/validate")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Service-Key","service-key")).andExpect(content().json("{\"token\":\"user-token\"}"))
            .andRespond(withSuccess("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"role\":\"USER\",\"active\":true,\"credentialVersion\":2}",MediaType.APPLICATION_JSON));
        var account=adapter.validate("user-token");
        assertEquals("00000000-0000-0000-0000-000000000001",account.id().toString());
        assertEquals("USER",account.role()); assertTrue(account.active()); assertEquals(2L,account.credentialVersion());
        server.verify(); assertThrows(NullPointerException.class,()->new IdentityHttpAdapter(null));
    }

    @Test void distinguishesUserErrorsFromServiceAndProtocolFailures() {
        check(401,"{\"code\":\"UNAUTHORIZED\"}",UNAUTHORIZED);
        check(403,"{\"code\":\"FORBIDDEN\"}",FORBIDDEN);
        check(401,"{\"code\":\"SERVICE_UNAUTHORIZED\"}",UNAVAILABLE);
        check(401,"",UNAVAILABLE); check(401,"{}",UNAVAILABLE);
        check(401,"{\"code\":3}",UNAVAILABLE); check(401,"not-json",UNAVAILABLE);
        check(403,"{\"code\":\"OTHER\"}",UNAVAILABLE);
        check(500,"{\"code\":\"UNAUTHORIZED\"}",UNAVAILABLE);
        check(404,"{}",UNAVAILABLE); check(429,"{}",UNAVAILABLE);
        check(200,"",UNAVAILABLE); check(204,"",UNAVAILABLE);
        check(201,"{}",UNAVAILABLE); check(200,"not-json",UNAVAILABLE);
        check(200,"{\"id\":\"invalid\"}",UNAVAILABLE);
    }

    void check(int status,String body,VideoAccessException.Reason expected) {
        server.reset(); server.expect(requestTo("http://identity/internal/accounts/validate"))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
        var error=assertThrows(VideoAccessException.class,()->adapter.validate("private-token"));
        assertEquals(expected,error.reason()); assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private-token")); server.verify();
    }

    @Test void timeoutAndTransportFailureAreUnavailableWithoutLeakingRequest() {
        server.expect(requestTo("http://identity/internal/accounts/validate"))
            .andRespond(withException(new IOException("private-token transport timeout")));
        var error=assertThrows(VideoAccessException.class,()->adapter.validate("private-token"));
        assertEquals(UNAVAILABLE,error.reason()); assertNull(error.getCause()); server.verify();
    }
}
