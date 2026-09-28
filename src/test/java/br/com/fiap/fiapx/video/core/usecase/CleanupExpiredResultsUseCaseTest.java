package br.com.fiap.fiapx.video.core.usecase;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import br.com.fiap.fiapx.video.core.gateway.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CleanupExpiredResultsUseCaseTest {
    DownloadGateway gateway=mock(DownloadGateway.class);
    ResultCleanupStorageGateway storage=mock(ResultCleanupStorageGateway.class);
    AtomicLong nano=new AtomicLong();
    Duration lease=Duration.ofSeconds(120),retry=Duration.ofSeconds(300);
    CleanupExpiredResultsUseCase cleanup=new CleanupExpiredResultsUseCase(gateway,storage,lease,retry,2,nano::get);
    UUID video=UUID.randomUUID(),owner=UUID.randomUUID(),token=UUID.randomUUID();
    DownloadArtifact artifact=new DownloadArtifact(video,owner,"fiapx-media-test",
            "results/"+owner+"/"+video+"/"+UUID.randomUUID()+"/frames.zip",100,"a".repeat(64));
    DownloadGateway.Cleanup claim=new DownloadGateway.Cleanup(token,artifact,Instant.now().plusSeconds(120));
    void oneClaim() { when(gateway.claimExpired(1,lease)).thenReturn(List.of(claim),List.of()); }
    @Test void commitsOneClaimBeforeDeleteAndConfirmsWithSameToken() {
        oneClaim(); when(gateway.deleted(video,token)).thenReturn(true);
        assertEquals(1,cleanup.execute());
        var order=inOrder(gateway,storage); order.verify(gateway).claimExpired(1,lease);
        order.verify(storage).delete(artifact); order.verify(gateway).deleted(video,token);
        order.verify(gateway).claimExpired(1,lease);
    }
    @Test void failedDeleteSchedulesRetryWithoutMarkingDeleted() {
        oneClaim(); doThrow(new IllegalStateException()).when(storage).delete(artifact);
        assertEquals(0,cleanup.execute()); verify(gateway).retryCleanup(video,token,retry); verify(gateway,never()).deleted(any(),any());
    }
    @Test void failedConfirmationAlsoLeavesDurableRetry() {
        oneClaim(); when(gateway.deleted(video,token)).thenThrow(new IllegalStateException());
        assertEquals(0,cleanup.execute()); verify(gateway).retryCleanup(video,token,retry);
    }
    @Test void oldTokenCannotReportSuccessfulDeletion() {
        oneClaim(); when(gateway.deleted(video,token)).thenReturn(false);
        assertEquals(0,cleanup.execute()); verify(gateway,never()).retryCleanup(any(),any(),any());
    }
    @Test void oldClaimNeverStartsNetworkCall() {
        when(gateway.claimExpired(1,lease)).thenAnswer(call->{nano.addAndGet(Duration.ofSeconds(101).toNanos());return List.of(claim);});
        assertEquals(0,cleanup.execute()); verifyNoInteractions(storage); verify(gateway,times(2)).retryCleanup(video,token,retry);
    }
    @Test void runIsBoundedAndConcurrentEntryDoesNotClaimAgain() {
        when(gateway.claimExpired(1,lease)).thenReturn(List.of(claim));
        doAnswer(call->{assertEquals(0,cleanup.execute());return null;}).when(storage).delete(artifact);
        when(gateway.deleted(video,token)).thenReturn(true);
        assertEquals(2,cleanup.execute()); verify(gateway,times(2)).claimExpired(1,lease);
    }
    @Test void databaseOutageResetsLocalGuardAndLeavesClaimForExpiration() {
        oneClaim(); doThrow(new IllegalStateException()).when(storage).delete(artifact);
        when(gateway.retryCleanup(video,token,retry)).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class,cleanup::execute); assertEquals(0,cleanup.execute());
    }
    @Test void invalidLimitsFailStartup() {
        assertThrows(IllegalArgumentException.class,()->new CleanupExpiredResultsUseCase(gateway,storage,Duration.ofSeconds(44),retry,1,nano::get));
        assertThrows(IllegalArgumentException.class,()->new CleanupExpiredResultsUseCase(gateway,storage,lease,Duration.ZERO,1,nano::get));
        assertThrows(IllegalArgumentException.class,()->new CleanupExpiredResultsUseCase(gateway,storage,lease,retry,101,nano::get));
    }
}
