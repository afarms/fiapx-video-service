package br.com.fiap.fiapx.video.infrastructure.messaging;

import java.util.*;
import org.junit.jupiter.api.Test;
import static br.com.fiap.fiapx.video.ProcessingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingResultDecoderTest {
    final ProcessingResultDecoder decoder=new ProcessingResultDecoder(JSON,BUCKET);
    Map<String,Object> body() { return envelope("ProcessingCompleted",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2); }
    @Test void matchesProducerEnvelopesAndCanonicalizesEquivalentJsonOrderAndTimes() {
        for (var type:List.of("ProcessingStarted","ProcessingCompleted","ProcessingFailed")) {
            var body=envelope(type,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2);
            var decoded=decode(body); var reordered=new TreeMap<>(body);
            reordered.put("occurredAt","2026-09-28T09:00:00-03:00");
            assertEquals(decoded,decoder.decode(JSON.writeValueAsString(reordered)));
        }
    }
    @Test void rejectsMalformedOrUnsupportedFieldsWithoutCoercion() {
        for (var entry:List.of(Map.entry("schemaVersion",(Object)2),Map.entry("schemaVersion","1"),Map.entry("eventType","Other"),
                Map.entry("eventType",4),Map.entry("eventId","1-1-1-1-1"),Map.entry("ownerId","bad"),Map.entry("payload",List.of()),
                Map.entry("extra","unexpected"))) {
            var body=body(); body.put(entry.getKey(),entry.getValue()); assertThrows(RuntimeException.class,()->decode(body));
        }
        for (var entry:List.of(Map.entry("bucket",(Object)"other-bucket"),Map.entry("sizeBytes",1.5),
                Map.entry("sizeBytes",new java.math.BigInteger("9223372036854775808")),Map.entry("sha256",1),Map.entry("extra",1))) {
            var body=body(); payload(body).put(entry.getKey(),entry.getValue()); assertThrows(RuntimeException.class,()->decode(body));
        }
        for (String body:List.of("{}","[]","null","{","x".repeat(16385))) assertThrows(RuntimeException.class,()->decoder.decode(body));
        assertThrows(IllegalArgumentException.class,()->decoder.decode(null));
        assertThrows(IllegalArgumentException.class,()->new ProcessingResultDecoder(JSON,null));
        assertThrows(IllegalArgumentException.class,()->new ProcessingResultDecoder(JSON,""));
    }
}
