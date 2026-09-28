package br.com.fiap.fiapx.video;

import java.time.Instant;
import java.util.*;
import br.com.fiap.fiapx.video.core.domain.*;
import br.com.fiap.fiapx.video.infrastructure.messaging.ProcessingResultDecoder;
import tools.jackson.databind.json.JsonMapper;

public final class ProcessingFixtures {
    public static final Instant NOW=Instant.parse("2026-09-28T12:00:00Z");
    public static final String BUCKET="fiapx-media-test";
    public static final JsonMapper JSON=JsonMapper.builder().build();
    public static Map<String,Object> envelope(String type,UUID video,UUID owner,UUID correlation,long version) {
        var payload=new LinkedHashMap<String,Object>();
        payload.put("jobId",video.toString()); payload.put("attemptId",UUID.randomUUID().toString());
        payload.put("attempt",1); payload.put("version",version);
        switch(type) {
            case "ProcessingStarted" -> payload.put("startedAt",NOW.toString());
            case "ProcessingFailed" -> { payload.put("failedAt",NOW.toString()); payload.put("failureCode","INVALID_MEDIA"); }
            case "ProcessingCompleted" -> {
                payload.put("completedAt",NOW.toString()); payload.put("expiresAt",NOW.plusSeconds(86400).toString());
                payload.put("bucket",BUCKET); payload.put("objectKey","results/"+owner+"/"+video+"/"+UUID.randomUUID()+"/frames.zip");
                payload.put("sizeBytes",100); payload.put("sha256","a".repeat(64)); payload.put("frameCount",1);
            }
            default -> throw new IllegalArgumentException();
        }
        var root=new LinkedHashMap<String,Object>();
        root.put("eventId",UUID.randomUUID().toString()); root.put("eventType",type); root.put("schemaVersion",1);
        root.put("aggregateId",video.toString()); root.put("ownerId",owner.toString()); root.put("correlationId",correlation.toString());
        root.put("occurredAt",NOW.toString()); root.put("payload",payload); return root;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> payload(Map<String,Object> envelope) { return (Map<String,Object>)envelope.get("payload"); }
    public static ProcessingResultEvent decode(Map<String,Object> envelope) {
        return new ProcessingResultDecoder(JSON,BUCKET).decode(JSON.writeValueAsString(envelope));
    }
    public static Video video(UUID id,UUID owner,VideoStatus status) {
        return new Video(id,owner,"sample.mp4","originals/"+owner+"/"+id+"/"+UUID.randomUUID(),100,NOW,status);
    }
}
