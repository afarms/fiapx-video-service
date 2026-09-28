package br.com.fiap.fiapx.video.infrastructure.security;

import br.com.fiap.fiapx.video.core.exception.VideoAccessException;
import br.com.fiap.fiapx.video.core.usecase.AuthorizeVideoAccessUseCase;
import br.com.fiap.fiapx.video.infrastructure.web.UploadFiles;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.io.IOException;
import java.util.UUID;

public class UploadAdmissionFilter extends OncePerRequestFilter {
    public static final String OWNER_ATTRIBUTE = UploadAdmissionFilter.class.getName() + ".owner";
    private final AuthorizeVideoAccessUseCase authorize;
    private final UploadFiles files;
    public UploadAdmissionFilter(AuthorizeVideoAccessUseCase authorize, UploadFiles files) {
        this.authorize = authorize;
        this.files = files;
    }
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean upload = "POST".equals(request.getMethod()) && "/videos".equals(request.getServletPath());
        String type = request.getContentType();
        boolean multipart = type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
        return !upload && !multipart;
    }
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !"/videos".equals(request.getServletPath())) {
            error(response, 415, "UNSUPPORTED_MULTIPART_TARGET"); return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) { error(response, 401, "UNAUTHORIZED"); return; }
        try {
            var jwt = token.getToken();
            var owner = authorize.execute(jwt.getTokenValue(), UUID.fromString(jwt.getSubject()),
                    ((Number) jwt.getClaim("ver")).longValue());
            request.setAttribute(OWNER_ATTRIBUTE, owner);
        } catch (VideoAccessException failure) {
            int status = switch (failure.reason()) { case UNAUTHORIZED -> 401; case FORBIDDEN -> 403; case UNAVAILABLE -> 503; };
            error(response, status, failure.reason().name()); return;
        }
        if (!files.acquire()) { error(response, 503, "UPLOAD_CAPACITY_UNAVAILABLE"); return; }
        try { chain.doFilter(request, response); } finally { files.release(); }
    }
    private void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }
}
