package br.com.fiap.fiapx.video.infrastructure.web;

import br.com.fiap.fiapx.video.core.domain.DownloadArtifact;
import br.com.fiap.fiapx.video.core.gateway.DownloadGateway;
import br.com.fiap.fiapx.video.core.exception.DownloadException;
import br.com.fiap.fiapx.video.infrastructure.storage.DownloadStorage;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadTransfersTest {
    DownloadGateway gateway = mock(DownloadGateway.class);
    DownloadStorage storage = mock(DownloadStorage.class);
    DownloadStorage.Body body = mock(DownloadStorage.Body.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    AsyncContext async = mock(AsyncContext.class);
    ServletOutputStream output = mock(ServletOutputStream.class);
    DownloadTransfers transfers;
    UUID owner = UUID.randomUUID(), video = UUID.randomUUID();
    DownloadGateway.Lease lease(long size) {
        return new DownloadGateway.Lease(UUID.randomUUID(), new DownloadArtifact(video, owner, "fiapx-media-test",
                "results/"+owner+"/"+video+"/"+UUID.randomUUID()+"/frames.zip", size, "a".repeat(64)),
                Instant.now().plusSeconds(30), Instant.now().plusSeconds(60));
    }
    @BeforeEach void setup() throws Exception {
        when(request.startAsync()).thenReturn(async);
        when(request.getProtocol()).thenReturn("HTTP/2.0");
        when(response.getOutputStream()).thenReturn(output);
        when(output.isReady()).thenReturn(true);
        when(storage.open(any())).thenReturn(body);
        transfers = new DownloadTransfers(gateway, storage, 1, Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(1));
    }
    @AfterEach void cleanup() { transfers.close(); }
    void start(DownloadGateway.Lease reservation, long started) {
        transfers.acquire(); transfers.start(request,response,reservation,started);
    }
    void finished(DownloadGateway.Lease reservation) {
        verify(gateway, timeout(3000)).release(video, reservation.token());
    }
    @Test void completeUsesBoundedBuffersAndSafeHeaders() throws Exception {
        byte[] bytes = new byte[150_000]; new Random(1).nextBytes(bytes);
        when(body.input()).thenReturn(new ByteArrayInputStream(bytes));
        var actual = new ByteArrayOutputStream();
        doAnswer(call -> { int count=call.getArgument(2); assertTrue(count<=65536);
            actual.write(call.getArgument(0),call.getArgument(1),count); return null;
        }).when(output).write(any(byte[].class),anyInt(),anyInt());
        var reservation=lease(bytes.length); start(reservation,System.nanoTime()); finished(reservation);
        assertArrayEquals(bytes,actual.toByteArray());
        verify(response).setContentLengthLong(bytes.length); verify(response).setContentType("application/zip");
        verify(response).setHeader("Accept-Ranges","none"); verify(response).setHeader("Cache-Control","private, no-store");
        verify(response,never()).reset(); verify(body,atLeastOnce()).abort(); verify(async).complete();
        verify(response,never()).setHeader("Connection","close");
    }
    @Test void openFailureReleasesReservationAndReturnsUnavailable() {
        when(storage.open(any())).thenThrow(new DownloadException(DownloadException.Reason.UNAVAILABLE));
        var reservation=lease(3); start(reservation,System.nanoTime()); finished(reservation);
        verify(response).setStatus(503); verify(response).reset(); verifyNoInteractions(output);
    }
    @Test void truncatedSourceDoesNotAppendErrorToCommittedZip() {
        when(body.input()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(response.isCommitted()).thenReturn(true);
        var reservation=lease(3); start(reservation,System.nanoTime()); finished(reservation);
        verify(response,never()).reset(); verify(response,never()).setStatus(503);
    }
    @Test void disconnectedClientAbortsUpstream() throws Exception {
        when(body.input()).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        doThrow(new IOException("disconnect")).when(output).write(any(byte[].class),anyInt(),anyInt());
        var reservation=lease(3); start(reservation,System.nanoTime()); finished(reservation);
        verify(body,atLeastOnce()).abort();
    }
    @Test void expiredLocalBudgetNeverOpensStorage() {
        var reservation=lease(3); start(reservation,System.nanoTime()-TimeUnit.SECONDS.toNanos(26)); finished(reservation);
        verifyNoInteractions(storage);
    }
    @Test void capacityRejectsExcessAndCanBeReturnedAfterFailedAdmission() {
        transfers.acquire(); assertThrows(DownloadException.class,transfers::acquire);
        transfers.cancelAdmission(); transfers.acquire(); transfers.cancelAdmission();
        transfers.close(); assertThrows(DownloadException.class,transfers::acquire);
    }
    @Test void rejectedRenewalInterruptsBlockedS3Read() throws Exception {
        var blocked=new CountDownLatch(1);
        when(body.input()).thenReturn(new InputStream() {
            public int read() throws IOException { try { blocked.await(2,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IOException("aborted"); }
        });
        doAnswer(call->{blocked.countDown();return null;}).when(body).abort();
        when(gateway.renew(any(),any(),any())).thenReturn(Optional.empty());
        var reservation=lease(3); start(reservation,System.nanoTime()-TimeUnit.SECONDS.toNanos(2)); finished(reservation);
        verify(gateway).renew(video,reservation.token(),Duration.ofSeconds(30)); verify(body,atLeastOnce()).abort();
    }
    @Test void slowClientCanBeAbortedWithoutWaitingForWriteReadiness() throws Exception {
        when(body.input()).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3})); when(output.isReady()).thenReturn(false);
        var reservation=lease(3); start(reservation,System.nanoTime());
        verify(output,timeout(2000)).setWriteListener(any());
        transfers.close(); finished(reservation); verify(output,never()).write(any(byte[].class),anyInt(),anyInt());
    }
    @Test void failedReleaseDoesNotPreventHttpCompletion() {
        when(body.input()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        doThrow(new IllegalStateException()).when(gateway).release(any(),any());
        var reservation=lease(1); start(reservation,System.nanoTime()); finished(reservation); verify(async).complete();
    }
    @Test void maximumDeadlineCannotBeExtendedBySuccessfulRenewal() throws Exception {
        transfers.close();
        transfers=new DownloadTransfers(gateway,storage,1,Duration.ofSeconds(30),Duration.ofSeconds(30),Duration.ofSeconds(1));
        var unblock=new CountDownLatch(1);
        when(body.input()).thenReturn(new InputStream() {
            public int read() throws IOException { try { unblock.await(3,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IOException("aborted"); }
        });
        doAnswer(call->{unblock.countDown();return null;}).when(body).abort();
        when(gateway.renew(any(),any(),any())).thenReturn(Optional.of(Instant.now().plusSeconds(30)));
        var reservation=lease(3); start(reservation,System.nanoTime()-TimeUnit.SECONDS.toNanos(24)); finished(reservation);
        verify(gateway,atLeastOnce()).renew(video,reservation.token(),Duration.ofSeconds(30)); verify(body,atLeastOnce()).abort();
    }
    @Test void stalledRenewalCannotBlockIndependentLeaseWatchdog() throws Exception {
        var unblockRead=new CountDownLatch(1); var unblockRenewal=new CountDownLatch(1); var renewing=new CountDownLatch(1);
        when(body.input()).thenReturn(new InputStream() {
            public int read() throws IOException { try { unblockRead.await(3,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IOException("aborted"); }
        });
        doAnswer(call->{unblockRead.countDown();return null;}).when(body).abort();
        when(gateway.renew(any(),any(),any())).thenAnswer(call->{renewing.countDown();unblockRenewal.await(5,TimeUnit.SECONDS);return Optional.of(Instant.now().plusSeconds(30));});
        var reservation=lease(3);
        try {
            start(reservation,System.nanoTime()-TimeUnit.SECONDS.toNanos(24));
            assertTrue(renewing.await(2,TimeUnit.SECONDS)); finished(reservation);
            assertEquals(1,unblockRenewal.getCount()); verify(body,atLeastOnce()).abort();
        } finally { unblockRenewal.countDown(); }
    }
    @Test void containerTimeoutCompletesExplicitlyWithoutDefaultErrorDispatch() throws Exception {
        when(body.input()).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3})); when(output.isReady()).thenReturn(false);
        var reservation=lease(3); start(reservation,System.nanoTime());
        verify(output,timeout(2000)).setWriteListener(any());
        var listener=org.mockito.ArgumentCaptor.forClass(AsyncListener.class); verify(async).addListener(listener.capture());
        var event=new AsyncEvent(async);
        listener.getValue().onStartAsync(event); listener.getValue().onTimeout(event);
        listener.getValue().onError(event); listener.getValue().onComplete(event);
        finished(reservation); verify(async).complete();
    }
    @Test void writeReadinessResumesSlowClientAndHttp11ClosesConnection() throws Exception {
        when(request.getProtocol()).thenReturn("HTTP/1.1");
        when(body.input()).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3})); when(output.isReady()).thenReturn(false);
        var reservation=lease(3); start(reservation,System.nanoTime());
        var listener=org.mockito.ArgumentCaptor.forClass(WriteListener.class);
        verify(output,timeout(2000)).setWriteListener(listener.capture());
        when(output.isReady()).thenReturn(true); listener.getValue().onWritePossible();
        finished(reservation); verify(response).setHeader("Connection","close");
    }
    @Test void validatesOperationalLimits() {
        assertThrows(IllegalArgumentException.class,()->new DownloadTransfers(gateway,storage,0,Duration.ofSeconds(30),Duration.ofSeconds(60),Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new DownloadTransfers(gateway,storage,1,Duration.ofSeconds(20),Duration.ofSeconds(60),Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new DownloadTransfers(gateway,storage,1,Duration.ofSeconds(30),Duration.ofSeconds(20),Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new DownloadTransfers(gateway,storage,1,Duration.ofSeconds(30),Duration.ofSeconds(60),Duration.ofSeconds(11)));
    }
}
