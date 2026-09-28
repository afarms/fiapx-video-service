package br.com.fiap.fiapx.video.infrastructure.persistence.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.fiap.fiapx.video.core.exception.*;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringDownloadRepository;
import br.com.fiap.fiapx.video.infrastructure.persistence.mapper.DownloadMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

class DownloadGatewayAdapterTest {
    final UUID id=UUID.randomUUID(), owner=UUID.randomUUID(), token=UUID.randomUUID();
    final Instant now=Instant.parse("2026-09-28T12:00:00Z");
    final Duration lease=Duration.ofSeconds(120), maximum=Duration.ofSeconds(1800);
    SpringDownloadRepository repository;
    SpringDownloadRepository.ResultRow row;
    PlatformTransactionManager manager;
    DownloadGatewayAdapter gateway;
    @BeforeEach void setup() {
        repository=mock(SpringDownloadRepository.class); row=mock(SpringDownloadRepository.ResultRow.class);
        manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        gateway=new DownloadGatewayAdapter(repository,new DownloadMapper(),tx);
        when(repository.lock(id)).thenReturn(Optional.of(row)); when(repository.now()).thenReturn(now);
        when(row.getId()).thenReturn(id); when(row.getOwnerId()).thenReturn(owner);
        when(row.getStatus()).thenReturn("COMPLETED"); when(row.getExpiresAt()).thenReturn(now.plusSeconds(3600));
        when(row.getResultBucket()).thenReturn("fiapx-media-test"); when(row.getResultObjectKey()).thenReturn("results/"+owner+"/"+id+"/"+UUID.randomUUID()+"/frames.zip");
        when(row.getResultSizeBytes()).thenReturn(100L); when(row.getResultSha256()).thenReturn("a".repeat(64));
        when(repository.insertLease(any(),eq(id),eq(now),any(),any())).thenReturn(1);
    }
    @Test void reserveCommitsAndDoesNotExposeATransferOnFailedCommit() {
        var reservation=gateway.reserve(id,owner,lease,maximum);
        assertEquals(now.plus(lease),reservation.validUntil()); assertEquals(now.plus(maximum),reservation.deadline());
        assertEquals(id,reservation.artifact().videoId()); verify(manager).commit(any());
        doThrow(new TransactionSystemException("commit uncertain")).when(manager).commit(any());
        var failure=assertThrows(DownloadException.class,()->gateway.reserve(id,owner,lease,maximum));
        assertEquals(DownloadException.Reason.UNAVAILABLE,failure.reason()); assertNotNull(failure.getCause());
    }
    @Test void ownerAndEligibilityAreCheckedBeforePrivateReferenceOrReservation() {
        assertThrows(VideoNotFoundException.class,()->gateway.reserve(id,UUID.randomUUID(),lease,maximum));
        when(repository.lock(id)).thenReturn(Optional.empty());
        assertThrows(VideoNotFoundException.class,()->gateway.reserve(id,owner,lease,maximum));
        when(repository.lock(id)).thenReturn(Optional.of(row)); when(row.getStatus()).thenReturn("QUEUED");
        assertEquals(DownloadException.Reason.NOT_READY,assertThrows(DownloadException.class,()->gateway.reserve(id,owner,lease,maximum)).reason());
        when(row.getStatus()).thenReturn("COMPLETED"); when(row.getExpiresAt()).thenReturn(now);
        assertEquals(DownloadException.Reason.EXPIRED,assertThrows(DownloadException.class,()->gateway.reserve(id,owner,lease,maximum)).reason());
        when(row.getExpiresAt()).thenReturn(now.plusSeconds(10)); when(row.getResultDeletedAt()).thenReturn(now);
        assertThrows(DownloadException.class,()->gateway.reserve(id,owner,lease,maximum));
        when(row.getResultDeletedAt()).thenReturn(null); when(row.getResultCleanupToken()).thenReturn(token);
        assertThrows(DownloadException.class,()->gateway.reserve(id,owner,lease,maximum));
        verify(repository,never()).insertLease(any(),any(),any(),any(),any()); verify(row,never()).getResultBucket();
    }
    @Test void failedInsertOrDatabaseReadCannotGrantTransfer() {
        when(repository.insertLease(any(),any(),any(),any(),any())).thenReturn(0);
        assertThrows(DownloadException.class,()->gateway.reserve(id,owner,lease,maximum));
        when(repository.lock(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertEquals(DownloadException.Reason.UNAVAILABLE,assertThrows(DownloadException.class,()->gateway.reserve(id,owner,lease,maximum)).reason());
    }
    @Test void renewalReleaseAndMissingOrLostLeases() {
        when(repository.renew(eq(id),eq(token),any())).thenReturn(1);
        when(repository.leaseUntil(id,token)).thenReturn(now.plusSeconds(5));
        assertEquals(Optional.of(now.plusSeconds(5)),gateway.renew(id,token,lease));
        when(repository.renew(eq(id),eq(token),any())).thenReturn(0); assertTrue(gateway.renew(id,token,lease).isEmpty());
        when(row.getResultCleanupToken()).thenReturn(token); assertTrue(gateway.renew(id,token,lease).isEmpty());
        when(row.getResultCleanupToken()).thenReturn(null); when(row.getResultDeletedAt()).thenReturn(now);
        assertTrue(gateway.renew(id,token,lease).isEmpty());
        when(repository.lock(id)).thenReturn(Optional.empty()); assertTrue(gateway.renew(id,token,lease).isEmpty());
        gateway.release(id,token); gateway.release(id,token); verify(repository,times(2)).release(id,token);
    }
    @Test void cleanupClaimsAreImmutableAndResultsAreFenced() {
        when(repository.lockExpired(2)).thenReturn(List.of(row)); when(repository.claim(eq(id),any(),any())).thenReturn(1);
        var claims=gateway.claimExpired(2,lease); assertEquals(1,claims.size()); assertEquals(id,claims.getFirst().artifact().videoId());
        assertThrows(UnsupportedOperationException.class,claims::clear);
        when(repository.claim(eq(id),any(),any())).thenReturn(0); assertThrows(DownloadException.class,()->gateway.claimExpired(2,lease));
        assertFalse(gateway.deleted(id,token)); when(repository.deleted(id,token)).thenReturn(1); assertTrue(gateway.deleted(id,token));
        assertFalse(gateway.retryCleanup(id,token,lease)); when(repository.retry(eq(id),eq(token),any())).thenReturn(1);
        assertTrue(gateway.retryCleanup(id,token,lease));
    }
    @Test void durationsAndBatchBoundsAreValidatedBeforeDatabaseAccess() {
        for(Duration bad:Arrays.asList(null,Duration.ZERO,Duration.ofSeconds(-1),Duration.ofMillis(1),Duration.ofSeconds(86401)))
            assertThrows(IllegalArgumentException.class,()->gateway.reserve(id,owner,bad,maximum));
        assertThrows(IllegalArgumentException.class,()->gateway.reserve(id,owner,maximum,lease));
        assertThrows(IllegalArgumentException.class,()->gateway.claimExpired(0,lease));
        assertThrows(IllegalArgumentException.class,()->gateway.claimExpired(101,lease));
        verifyNoInteractions(manager);
    }
}
