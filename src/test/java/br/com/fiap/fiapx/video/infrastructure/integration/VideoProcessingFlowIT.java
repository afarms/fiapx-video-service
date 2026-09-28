package br.com.fiap.fiapx.video.infrastructure.integration;

import br.com.fiap.fiapx.video.infrastructure.messaging.*;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP/SQL in both services and FFmpeg in Docker; AWS/identity are simulated. */
class VideoProcessingFlowIT extends UploadPostgresIT {
    @Test void roundTrip() throws Exception {
        Path flow=Path.of(Objects.requireNonNull(System.getenv("FLOW_DIRECTORY"))).toAbsolutePath().normalize();
        var s3=context.getBean("testS3",S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenAnswer(call->{
            PutObjectRequest request=call.getArgument(0); RequestBody body=call.getArgument(1);
            Path root=flow.resolve("objects"); Path target=root.resolve(request.key()).normalize(); assertTrue(target.startsWith(root));
            Files.createDirectories(target.getParent());
            try(var input=body.contentStreamProvider().newStream()) { Files.copy(input,target); }
            return PutObjectResponse.builder().build();
        });
        byte[] valid=Files.readAllBytes(flow.resolve("source.mp4")),invalid="invalid media".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID validOwner=UUID.randomUUID(),badOwner=UUID.randomUUID(),validKey=UUID.randomUUID();
        var receipt=postBytes(validOwner,validKey,valid); assertEquals(202,receipt.statusCode());
        var rejected=postBytes(badOwner,UUID.randomUUID(),invalid); assertEquals(202,rejected.statusCode());
        String validId=json.readTree(receipt.body()).path("id").asString(),badId=json.readTree(rejected.body()).path("id").asString();
        var sqs=mock(SqsClient.class); var requests=new ArrayList<String>();
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenAnswer(call->{requests.add(((SendMessageRequest)call.getArgument(0)).messageBody()); return SendMessageResponse.builder().messageId(UUID.randomUUID().toString()).build();});
        new OutboxDispatcher(context.getBean(SpringOutboxRepository.class),context.getBean(TransactionTemplate.class),sqs,
                "https://sqs.us-east-1.amazonaws.com/123456789012/work").dispatch();
        assertEquals(2,requests.size()); Files.writeString(flow.resolve("requests.json"),json.writeValueAsString(requests));
        runWorker(flow);
        var events=new ArrayList<>(List.of(json.readValue(Files.readString(flow.resolve("results.json")),String[].class)));
        events.sort(Comparator.comparing(body->json.readTree(body).path("eventType").asString().equals("ProcessingStarted")));
        var consumerSqs=mock(SqsClient.class);
        try(var consumer=new ProcessingResultsConsumer(consumerSqs,"https://sqs.us-east-1.amazonaws.com/123456789012/events",
                new ProcessingResultDecoder(json,bucket()),results())) {
            // Apply terminal before started, then duplicate terminal publications from a work replay.
            events.addAll(List.of(json.readValue(Files.readString(flow.resolve("replayed-results.json")),String[].class)));
            for(String body:events) {
                when(consumerSqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().messages(
                        Message.builder().body(body).receiptHandle(UUID.randomUUID().toString()).messageId(UUID.randomUUID().toString()).build()).build());
                consumer.poll();
            }
            verify(consumerSqs,times(6)).deleteMessage(any(DeleteMessageRequest.class));
        }
        var completed=json.readTree(get(validOwner,"/"+validId).body()); assertEquals("COMPLETED",completed.path("status").asString());
        var failed=json.readTree(get(badOwner,"/"+badId).body()); assertEquals("FAILED",failed.path("status").asString()); assertEquals("INVALID_MEDIA",failed.path("failureCode").asString());
        var terminal=json.readTree(events.stream().filter(body->json.readTree(body).path("eventType").asString().equals("ProcessingCompleted")).findFirst().orElseThrow()).path("payload");
        assertEquals(java.time.Instant.parse(terminal.path("expiresAt").asString()),java.time.Instant.parse(completed.path("expiresAt").asString()));
        assertEquals(receipt.body(),postBytes(validOwner,validKey,valid).body());
        assertEquals(404,get(badOwner,"/"+validId).statusCode());
        for(String field:List.of("bucket","objectKey","sha256","resultBucket")) assertFalse(completed.has(field));
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM video_processing_inbox",Integer.class));
        context.close(); startApplication();
        assertEquals(completed,json.readTree(get(validOwner,"/"+validId).body()));
    }
    private HttpResponse<String> postBytes(UUID account,UUID key,byte[] data) throws Exception {
        String boundary="local-flow-multipart";
        byte[] header=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.mp4\"\r\nContent-Type: video/mp4\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail=("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder(URI.create(base+"/videos")).header("Authorization","Bearer "+account)
                .header("Idempotency-Key",key.toString()).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(header),HttpRequest.BodyPublishers.ofByteArray(data),HttpRequest.BodyPublishers.ofByteArray(tail))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void runWorker(Path flow) throws Exception {
        String database=Objects.requireNonNull(System.getenv("FLOW_DB_CONTAINER")); assertTrue(database.matches("[0-9a-f]{12,64}"));
        String name="fiapx-flow-"+UUID.randomUUID();
        var command=List.of("docker","run","--rm","--init","--name",name,"--cpus=2","--memory=1g","--network","container:"+database,
                "--mount","type=bind,source="+flow+",target=/flow","--mount","type=volume,source=fiapx-processing-media-maven,target=/root/.m2",
                "-e","PROCESSING_TEST_DB_URL","-e","PROCESSING_TEST_DB_USERNAME","-e","PROCESSING_TEST_DB_PASSWORD","-e","FLOW_DIRECTORY=/flow",
                "fiapx-processing-media-test:local","./mvnw","-B","-ntp","-Ppostgres-integration","-Dit.test=ProcessingFlowIT#processProducedRequests","verify");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(flow.resolve("worker.log").toFile()).start();
        try {
            assertTrue(process.waitFor(180,TimeUnit.SECONDS),"Worker timeout; inspect local worker.log");
            assertEquals(0,process.exitValue(),"Worker failed; inspect "+flow.resolve("worker.log"));
        } finally {
            if(process.isAlive()) { new ProcessBuilder("docker","rm","-f",name).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(30,TimeUnit.SECONDS); process.destroyForcibly(); }
        }
    }
}
