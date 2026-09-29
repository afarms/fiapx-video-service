package br.com.fiap.fiapx.video.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.servlet.ServletWebServerConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class PublicProxyTest {
    @Test
    void frameworkProxyPreservesPublicHttpsAndSwaggerPrefixDespiteHttpAlb() {
        new WebApplicationContextRunner()
            .withBean(ServerProperties.class, ServerProperties::new)
            .withUserConfiguration(ServletWebServerConfiguration.class)
            .withPropertyValues("server.forward-headers-strategy=framework")
            .run(context -> {
                assertNull(context.getStartupFailure());
                var filter = context.getBean("forwardedHeaderFilter", FilterRegistrationBean.class).getFilter();
                var request = new MockHttpServletRequest("GET", "/swagger-ui.html");
                request.setServerName("internal-alb");
                request.setServerPort(80);
                request.addHeader("Forwarded", "proto=https;host=\"example.cloudfront.net\"");
                request.addHeader("X-Forwarded-Proto", "http"); // ALB appends its actual protocol.
                request.addHeader("X-Forwarded-Prefix", "/api/video");
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> {
                    var forwarded = (HttpServletRequest) req;
                    assertTrue(forwarded.isSecure());
                    assertEquals("/api/video", forwarded.getContextPath());
                    assertEquals("https://example.cloudfront.net/api/video/swagger-ui.html",
                        forwarded.getRequestURL().toString());
                    ((HttpServletResponse) res).sendRedirect(forwarded.getContextPath() + "/swagger-ui/index.html");
                });
                assertEquals("https://example.cloudfront.net/api/video/swagger-ui/index.html", response.getRedirectedUrl());
            });
    }
}
