package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import br.com.fiap.fiapx.video.core.exception.DownloadException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3DownloadStorageTest {
    S3Client client=mock(S3Client.class);
    UUID video=UUID.randomUUID(), owner=UUID.randomUUID();
    DownloadArtifact artifact=new DownloadArtifact(video,owner,"fiapx-media-test",
            "results/"+owner+"/"+video+"/"+UUID.randomUUID()+"/frames.zip",3,"a".repeat(64));
    @Test void readsExactPersistedReferenceWithoutRangeAndClosesClient() throws Exception {
        var aborted=mock(Runnable.class);
        var stream=new ResponseInputStream<>(GetObjectResponse.builder().contentLength(3L).build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1,2,3}),aborted::run));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
        try(var storage=new S3DownloadStorage(client)) {
            var body=storage.open(artifact); assertArrayEquals(new byte[]{1,2,3},body.input().readAllBytes()); body.abort();
        }
        verify(client).getObject(GetObjectRequest.builder().bucket(artifact.bucket()).key(artifact.objectKey()).build());
        verify(aborted).run(); verify(client).close();
    }
    @Test void rejectsWrongObjectSizeAndAbortsWithoutReadingBody() {
        var aborted=mock(Runnable.class);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(4L).build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[4]),aborted::run)));
        assertThrows(DownloadException.class,()->new S3DownloadStorage(client).open(artifact)); verify(aborted).run();
    }
    @Test void missingObjectAndTransportFailureAreUnavailable() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertEquals(DownloadException.Reason.UNAVAILABLE,
                assertThrows(DownloadException.class,()->new S3DownloadStorage(client).open(artifact)).reason());
    }
}
