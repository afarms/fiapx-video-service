package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.core.exception.UploadException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import java.nio.file.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3OriginalStorageAdapterTest {
    @TempDir Path temporary;
    @Test void streamsFileWithChecksumAndNeverOverwritesAcceptedObject() throws Exception {
        var client = mock(S3Client.class);
        var adapter = new S3OriginalStorageAdapter(client, "media");
        Path file = Files.writeString(temporary.resolve("video"), "abc");
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            PutObjectRequest request = call.getArgument(0); RequestBody body = call.getArgument(1);
            assertEquals("media", request.bucket()); assertEquals("originals/key", request.key());
            assertEquals("*", request.ifNoneMatch()); assertEquals(Base64.getEncoder().encodeToString(new byte[32]), request.checksumSHA256());
            assertEquals(3, body.contentLength());
            try (var stream = body.contentStreamProvider().newStream()) { assertEquals("abc", new String(stream.readAllBytes())); }
            return null;
        });
        adapter.put("originals/key", file, "0".repeat(64));
        doThrow(new RuntimeException("secret details")).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertEquals(UploadException.Reason.UNAVAILABLE,
                assertThrows(UploadException.class, () -> adapter.put("originals/key", file, "0".repeat(64))).reason());
    }
}
