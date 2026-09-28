package br.com.fiap.fiapx.video.infrastructure.security;

import br.com.fiap.fiapx.video.core.exception.VideoAccessException;
import br.com.fiap.fiapx.video.core.usecase.AuthorizeVideoAccessUseCase;
import br.com.fiap.fiapx.video.infrastructure.web.UploadFiles;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadAdmissionFilterTest {
    final AuthorizeVideoAccessUseCase authorize = mock(AuthorizeVideoAccessUseCase.class);
    final UploadFiles files = mock(UploadFiles.class);
    final UploadAdmissionFilter filter = new UploadAdmissionFilter(authorize, files);
    final UUID owner = UUID.randomUUID();
    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/videos"); request.setServletPath("/videos"); return request;
    }
    void login() {
        var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(owner.toString()).claim("ver", 1L).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        when(authorize.execute("token", owner, 1L)).thenReturn(owner);
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }
    @Test void unrelatedRequestsDoNotAcquireSlotsOrConsultIdentity() throws Exception {
        var chain = mock(FilterChain.class);
        var get = request(); get.setMethod("GET"); filter.doFilter(get, new MockHttpServletResponse(), chain);
        var other = request(); other.setServletPath("/other"); filter.doFilter(other, new MockHttpServletResponse(), chain);
        verifyNoInteractions(authorize, files); verify(chain, times(2)).doFilter(any(), any());
    }
    @Test void anonymousRequestIsRejectedBeforeParsing() throws Exception {
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        filter.doFilter(request(), response, chain);
        assertEquals(401, response.getStatus()); assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        verifyNoInteractions(authorize, files, chain);
    }
    @Test void alternateRoutesAndMethodsCannotBypassMultipartAdmission() throws Exception {
        var chain = mock(FilterChain.class);
        var other = request(); other.setServletPath("/missing"); other.setContentType("multipart/form-data; boundary=test");
        var response = new MockHttpServletResponse(); filter.doFilter(other, response, chain);
        assertEquals(415, response.getStatus());
        var get = request(); get.setMethod("GET"); get.setContentType("multipart/mixed");
        response = new MockHttpServletResponse(); filter.doFilter(get, response, chain);
        assertEquals(415, response.getStatus()); verifyNoInteractions(chain, authorize, files);
    }
    @Test void identityErrorsAndCapacityRefusalDoNotReadTheBody() throws Exception {
        login(); var chain = mock(FilterChain.class);
        for (var reason : VideoAccessException.Reason.values()) {
            doThrow(new VideoAccessException(reason)).when(authorize).execute("token", owner, 1);
            var response = new MockHttpServletResponse(); filter.doFilter(request(), response, chain);
            assertEquals(switch(reason) {case UNAUTHORIZED -> 401; case FORBIDDEN -> 403; case UNAVAILABLE -> 503;}, response.getStatus());
        }
        doReturn(owner).when(authorize).execute("token", owner, 1);
        var response = new MockHttpServletResponse(); filter.doFilter(request(), response, chain);
        assertEquals(503, response.getStatus()); verify(files, never()).release(); verifyNoInteractions(chain);
    }
    @Test void releasesAdmissionEvenWhenDownstreamFails() throws Exception {
        login(); when(files.acquire()).thenReturn(true);
        var request = request(); var chain = mock(FilterChain.class);
        doThrow(new jakarta.servlet.ServletException("failure")).when(chain).doFilter(any(), any());
        assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), chain));
        assertEquals(owner, request.getAttribute(UploadAdmissionFilter.OWNER_ATTRIBUTE));
        verify(files).release();
    }
}
