package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import br.com.fiap.fiapx.video.infrastructure.web.UploadFiles;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadReconcilerTest {
    final SpringVideoRepository repository = mock(SpringVideoRepository.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final S3Client client = mock(S3Client.class);
    final UploadFiles files = mock(UploadFiles.class);
    final Instant now = Instant.parse("2026-09-27T12:00:00Z");
    final UploadReconciler reconciler = new UploadReconciler(repository, new TransactionTemplate(manager), client,
            "media", Clock.fixed(now, ZoneOffset.UTC), files);
    @BeforeEach void transaction() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.databaseTime()).thenReturn(now);
    }
    S3Object object(String key, boolean recent) {
        return S3Object.builder().key(key).lastModified(recent ? now : now.minusSeconds(901)).build();
    }
    VideoEntity video(String state, UUID key, Instant lease) {
        return new VideoEntity(UUID.randomUUID(), UUID.randomUUID(), "test.mp4", "key", 10, state, now.minusSeconds(1000),
                key, "a".repeat(64), UUID.randomUUID(), lease, "QUEUED".equals(state) ? now : null);
    }
    @Test void preservesAcceptedLegacyActiveAndRecentObjectsWhileDeletingOnlyUnreferencedOldOnes() throws Exception {
        var keys = List.of("accepted", "legacy", "active", "expired", "orphan");
        var objects = new ArrayList<>(keys.stream().map(k -> object("originals/" + k, false)).toList());
        objects.add(object("originals/recent", true));
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().contents(objects).build());
        when(repository.lockByOriginalKey("originals/accepted")).thenReturn(Optional.of(video("QUEUED", UUID.randomUUID(), now.minusSeconds(1))));
        when(repository.lockByOriginalKey("originals/legacy")).thenReturn(Optional.of(video("UPLOADING", null, null)));
        when(repository.lockByOriginalKey("originals/active")).thenReturn(Optional.of(video("UPLOADING", UUID.randomUUID(), now.plusSeconds(1))));
        when(repository.lockByOriginalKey("originals/expired")).thenReturn(Optional.of(video("UPLOADING", UUID.randomUUID(), now)));
        reconciler.reconcile();
        var deleted = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client, times(2)).deleteObject(deleted.capture());
        assertEquals(Set.of("originals/expired", "originals/orphan"), new HashSet<>(deleted.getAllValues().stream().map(DeleteObjectRequest::key).toList()));
        verify(files).cleanStaleFiles(); verify(repository, never()).lockByOriginalKey("originals/recent");
    }
    @Test void failedCleanupIsRetriedOnLaterTraversalIncludingLateWrites() throws Exception {
        var old = object("originals/orphan", false);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder().contents(old).nextContinuationToken("page2").build(),
                ListObjectsV2Response.builder().build(), ListObjectsV2Response.builder().contents(old).build());
        doThrow(new RuntimeException("offline")).doReturn(null).when(client).deleteObject(any(DeleteObjectRequest.class));
        reconciler.reconcile(); verify(repository, never()).cleaned(any());
        reconciler.reconcile(); reconciler.reconcile();
        var pages = org.mockito.ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client, times(3)).listObjectsV2(pages.capture());
        assertNull(pages.getAllValues().get(0).continuationToken());
        assertEquals("page2", pages.getAllValues().get(1).continuationToken());
        assertNull(pages.getAllValues().get(2).continuationToken());
        assertEquals("originals/", pages.getValue().prefix());
        verify(repository).cleaned("originals/orphan");
    }
    @Test void unavailableDatabaseNeverAuthorizesDeletion() throws Exception {
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().contents(object("originals/key", false)).build());
        when(repository.lockByOriginalKey(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        reconciler.reconcile(); verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
