package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.domain.Video;
import br.com.fiap.fiapx.video.core.exception.UploadException;
import br.com.fiap.fiapx.video.core.usecase.UploadVideoUseCase;
import br.com.fiap.fiapx.video.infrastructure.security.UploadAdmissionFilter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.Part;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoUploadControllerTest {
    @TempDir Path directory;
    final UploadVideoUseCase useCase = mock(UploadVideoUseCase.class);
    final MultipartHttpServletRequest request = mock(MultipartHttpServletRequest.class);
    final UUID owner = UUID.randomUUID(), key = UUID.randomUUID();
    final MultipartFile file = new MockMultipartFile("file", "sample.mp4", "video/mp4", "abc".getBytes());
    VideoUploadController controller;
    @BeforeEach void setup() throws Exception {
        controller = new VideoUploadController(useCase, new UploadFiles(directory, 1, 202_000_000, Clock.systemUTC()));
        when(request.getAttribute(UploadAdmissionFilter.OWNER_ATTRIBUTE)).thenReturn(owner);
        var map = new LinkedMultiValueMap<String, MultipartFile>(); map.add("file", file);
        when(request.getMultiFileMap()).thenReturn(map); when(request.getFiles("file")).thenReturn(List.of(file));
        when(request.getParts()).thenReturn(List.of(mock(Part.class)));
        when(request.getParameterMap()).thenReturn(Map.of());
    }
    @Test void responds202WithPrivateReceiptAndLocationThenRemovesTemp() throws Exception {
        var video = new Video(UUID.randomUUID(), owner, "sample.mp4", "private-key", 3, Instant.now()).queued();
        when(useCase.execute(eq(owner), eq(key), eq("sample.mp4"), eq(3L), any(), any())).thenReturn(video);
        var response = controller.upload(key.toString(), file, request);
        assertEquals(202, response.getStatusCode().value());
        assertEquals("/videos/" + video.id(), response.getHeaders().getLocation().toString());
        assertEquals("QUEUED", response.getBody().status());
        try(var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
    }
    @Test void rejectsInvalidKeysAndUnverifiedOwnerBeforeUseCase() {
        for (var invalid : List.of("invalid", "1-1-1-1-1"))
            assertEquals(UploadException.Reason.INVALID, assertThrows(UploadException.class, () -> controller.upload(invalid, file, request)).reason());
        when(request.getAttribute(anyString())).thenReturn(null);
        assertEquals(UploadException.Reason.UNAVAILABLE, assertThrows(UploadException.class, () -> controller.upload(key.toString(), file, request)).reason());
        verifyNoInteractions(useCase);
    }
    @Test void rejectsDuplicateFilesAdditionalFieldsAndParts() throws Exception {
        when(request.getFiles("file")).thenReturn(List.of(file, file));
        assertThrows(UploadException.class, () -> controller.upload(key.toString(), file, request));
        when(request.getFiles("file")).thenReturn(List.of(file));
        when(request.getParameterMap()).thenReturn(Map.of("ownerId", new String[]{"forged"}));
        assertThrows(UploadException.class, () -> controller.upload(key.toString(), file, request));
        when(request.getParameterMap()).thenReturn(Map.of());
        when(request.getParts()).thenReturn(List.of(mock(Part.class), mock(Part.class)));
        assertThrows(UploadException.class, () -> controller.upload(key.toString(), file, request));
        when(request.getMultiFileMap()).thenReturn(new LinkedMultiValueMap<>());
        assertThrows(UploadException.class, () -> controller.upload(key.toString(), file, request));
        var plain = new MockHttpServletRequest(); plain.setAttribute(UploadAdmissionFilter.OWNER_ATTRIBUTE, owner);
        assertThrows(UploadException.class, () -> controller.upload(key.toString(), file, plain));
        verifyNoInteractions(useCase);
    }
    @Test void dependencyFailureStillCleansTempAndErrorsHaveSanitizedCodes() throws Exception {
        when(useCase.execute(any(), any(), any(), anyLong(), any(), any())).thenThrow(new UploadException(UploadException.Reason.UNAVAILABLE));
        assertThrows(UploadException.class, () -> controller.upload(key.toString(), file, request));
        try(var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
        var errors = new VideoApiErrors();
        for(var reason : UploadException.Reason.values()) {
            var response = errors.upload(new UploadException(reason, new RuntimeException("sensitive")));
            assertEquals("UPLOAD_" + reason, response.getBody().getProperties().get("code"));
            assertFalse(response.getBody().getDetail().contains("sensitive"));
        }
        assertEquals(413, errors.tooLarge(new RuntimeException()).getStatusCode().value());
        assertEquals(400, errors.malformedMultipart(new RuntimeException()).getStatusCode().value());
        assertEquals(400, errors.malformedUpload(new RuntimeException()).getStatusCode().value());
        assertEquals(503, errors.uploadReadFailure(new java.io.IOException()).getStatusCode().value());
        assertEquals(415, errors.unsupportedMedia(new RuntimeException()).getStatusCode().value());
    }
}
