package br.com.fiap.fiapx.video.core.domain;

import java.util.*;
import org.junit.jupiter.api.Test;
import static br.com.fiap.fiapx.video.ProcessingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingResultEventTest {
    final UUID video=UUID.randomUUID(),owner=UUID.randomUUID(),correlation=UUID.randomUUID();
    Map<String,Object> event(String type,long version) { return envelope(type,video,owner,correlation,version); }
    @Test void completedBeforeStartedAndLateStartedNeverRegressTerminal() {
        var completed=decode(event("ProcessingCompleted",2));
        assertTrue(completed.shouldApply(video(video,owner,VideoStatus.QUEUED),0));
        assertEquals(VideoStatus.COMPLETED,completed.status());
        assertNotEquals(completed.attemptId().toString(),completed.result().objectKey().split("/")[3]);
        assertEquals(NOW.plusSeconds(86400),completed.expiresAt());
        var started=decode(event("ProcessingStarted",1));
        assertFalse(started.shouldApply(video(video,owner,VideoStatus.COMPLETED),2));
        assertFalse(decode(event("ProcessingStarted",3)).shouldApply(video(video,owner,VideoStatus.FAILED),2));
        assertThrows(IllegalArgumentException.class,()->decode(event("ProcessingFailed",3)).shouldApply(video(video,owner,VideoStatus.COMPLETED),2));
    }
    @Test void rejectsUnacceptedWrongOwnerWrongVideoAndVersionCollision() {
        var started=decode(event("ProcessingStarted",1));
        assertEquals(VideoStatus.PROCESSING,started.status());
        assertTrue(started.shouldApply(video(video,owner,VideoStatus.QUEUED),0));
        assertFalse(started.shouldApply(video(video,owner,VideoStatus.PROCESSING),2));
        assertThrows(IllegalArgumentException.class,()->started.shouldApply(video(video,owner,VideoStatus.UPLOADING),0));
        assertThrows(IllegalArgumentException.class,()->started.shouldApply(video(video,UUID.randomUUID(),VideoStatus.QUEUED),0));
        assertThrows(IllegalArgumentException.class,()->started.shouldApply(video(UUID.randomUUID(),owner,VideoStatus.QUEUED),0));
        assertThrows(IllegalArgumentException.class,()->started.shouldApply(video(video,owner,VideoStatus.PROCESSING),1));
        assertEquals(VideoStatus.FAILED,decode(event("ProcessingFailed",2)).status());
    }
    @Test void validatesEveryResultBoundaryWithoutInferringExpirationFromReceiveTime() {
        for (var entry:List.of(Map.entry("jobId",(Object)UUID.randomUUID().toString()),Map.entry("attempt",0),Map.entry("attempt",2147483648L),
                Map.entry("version",0),Map.entry("expiresAt",NOW.toString()),Map.entry("completedAt",NOW.minusSeconds(1).toString()),
                Map.entry("objectKey","results/other"),Map.entry("objectKey","results/"+owner+"/"+video+"/../frames.zip"),
                Map.entry("sizeBytes",0),Map.entry("sizeBytes",1073741825L),Map.entry("sha256","bad"),
                Map.entry("frameCount",0),Map.entry("frameCount",2147483648L))) {
            var body=event("ProcessingCompleted",2); payload(body).put(entry.getKey(),entry.getValue());
            assertThrows(IllegalArgumentException.class,()->decode(body),entry.toString());
        }
        var failure=event("ProcessingFailed",2); payload(failure).put("failureCode","raw stderr");
        assertThrows(IllegalArgumentException.class,()->decode(failure));
        var start=event("ProcessingStarted",1); payload(start).put("startedAt",NOW.plusSeconds(1).toString());
        assertThrows(IllegalArgumentException.class,()->decode(start));
    }
}
