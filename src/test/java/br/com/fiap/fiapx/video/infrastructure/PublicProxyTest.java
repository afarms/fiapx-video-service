package br.com.fiap.fiapx.video.infrastructure;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.usecase.UploadVideoUseCase;
import br.com.fiap.fiapx.video.infrastructure.security.UploadAdmissionFilter;
import br.com.fiap.fiapx.video.infrastructure.web.UploadFiles;
import br.com.fiap.fiapx.video.infrastructure.web.VideoUploadController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
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
    @TempDir Path directory;

    @Test
    void actualUploadResponseLocationIncludesPublicPrefixAndStillWorksWithoutProxy() throws Exception {
        var useCase = Mockito.mock(UploadVideoUseCase.class);
        var owner = UUID.randomUUID();
        var key = UUID.randomUUID();
        var video = new Video(UUID.randomUUID(), owner,
            "sample.mp4", "private-key", 3, Instant.now()).queued();
        Mockito.when(useCase.execute(ArgumentMatchers.eq(owner),
            ArgumentMatchers.eq(key), ArgumentMatchers.eq("sample.mp4"),
            ArgumentMatchers.eq(3L), ArgumentMatchers.any(),
            ArgumentMatchers.any())).thenReturn(video);
        var files = new UploadFiles(directory, 1, 202_000_000, Clock.systemUTC());
        new WebApplicationContextRunner()
            .withBean(ServerProperties.class, ServerProperties::new)
            .withUserConfiguration(ServletWebServerConfiguration.class, UploadMvc.class)
            .withBean(VideoUploadController.class,
                () -> new VideoUploadController(useCase, files))
            .withPropertyValues("server.forward-headers-strategy=framework", "upload.enabled=true")
            .run(context -> {
                assertNull(context.getStartupFailure());
                var filter = context.getBean("forwardedHeaderFilter", FilterRegistrationBean.class).getFilter();
                var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(filter).build();
                for (var prefix : new String[]{"", "/api/video"}) {
                    var request = MockMvcRequestBuilders.post("/videos")
                        .contentType("multipart/form-data; boundary=test")
                        .header("Idempotency-Key", key.toString())
                        .with(req -> {
                            req.addPart(new MockPart("file", "sample.mp4", "abc".getBytes(StandardCharsets.UTF_8)) {
                                @Override
                                public void delete() { /* In-memory part has no temporary file to delete. */ }
                            });
                            req.setAttribute(UploadAdmissionFilter.OWNER_ATTRIBUTE, owner);
                            return req;
                        });
                    if (!prefix.isEmpty()) {
                        request.header("Forwarded", "proto=https;host=\"example.cloudfront.net\"")
                            .header("X-Forwarded-Proto", "http").header("X-Forwarded-Prefix", prefix);
                    }
                    var response = mvc.perform(request).andReturn().getResponse();
                    assertEquals(202, response.getStatus(), response.getContentAsString());
                    assertEquals(prefix + "/videos/" + video.id(), response.getHeader("Location"));
                    assertFalse(response.getContentAsString().contains("private-key"));
                }
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class UploadMvc {
        @Bean
        StandardServletMultipartResolver multipartResolver() {
            return new StandardServletMultipartResolver();
        }
    }

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
