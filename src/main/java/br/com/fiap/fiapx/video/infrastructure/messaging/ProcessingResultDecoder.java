package br.com.fiap.fiapx.video.infrastructure.messaging;

import br.com.fiap.fiapx.video.core.domain.ProcessingResultEvent;
import br.com.fiap.fiapx.video.core.domain.ProcessingResultEvent.*;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ProcessingResultDecoder {
    private final JsonMapper json;
    private final String bucket;
    public ProcessingResultDecoder(JsonMapper json,String bucket) {
        if (bucket==null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) throw new IllegalArgumentException("Configure result bucket");
        this.json=json; this.bucket=bucket;
    }
    public ProcessingResultEvent decode(String body) {
        if (body==null || body.length()>16384) throw new IllegalArgumentException("Invalid envelope size");
        var root=json.readTree(body);
        if (!root.isObject() || root.size()!=8 || number(root,"schemaVersion")!=1) throw new IllegalArgumentException("Unsupported envelope");
        var type=Type.valueOf(text(root,"eventType")); var p=root.path("payload");
        if (!p.isObject() || p.size()!=(type==Type.ProcessingStarted?5:type==Type.ProcessingFailed?6:11))
            throw new IllegalArgumentException("Unexpected result fields");
        ResultReference result=null; Instant started=null,completed=null,expires=null,failed=null; String code=null;
        switch (type) {
            case ProcessingStarted -> started=time(p,"startedAt");
            case ProcessingFailed -> { failed=time(p,"failedAt"); code=text(p,"failureCode"); }
            case ProcessingCompleted -> {
                if (!bucket.equals(text(p,"bucket"))) throw new IllegalArgumentException("Unexpected result bucket");
                completed=time(p,"completedAt"); expires=time(p,"expiresAt");
                result=new ResultReference(bucket,text(p,"objectKey"),number(p,"sizeBytes"),text(p,"sha256"),number(p,"frameCount"));
            }
        }
        return new ProcessingResultEvent(uuid(root,"eventId"),uuid(root,"aggregateId"),uuid(root,"ownerId"),uuid(root,"correlationId"),
                time(root,"occurredAt"),type,uuid(p,"jobId"),uuid(p,"attemptId"),number(p,"attempt"),number(p,"version"),
                started,completed,expires,failed,code,result);
    }
    private static String text(JsonNode node,String field) {
        var value=node.path(field); if (!value.isString()) throw new IllegalArgumentException("Invalid result field"); return value.asString();
    }
    private static long number(JsonNode node,String field) {
        var value=node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid number");
        return value.longValue();
    }
    private static UUID uuid(JsonNode node,String field) {
        String value=text(node,field); UUID id=UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Invalid UUID"); return id;
    }
    private static Instant time(JsonNode node,String field) { return Instant.parse(text(node,field)); }
}
