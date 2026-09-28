package br.com.fiap.fiapx.video.infrastructure.integration;

import br.com.fiap.fiapx.video.infrastructure.VideoApplication;
import br.com.fiap.fiapx.video.infrastructure.messaging.OutboxDispatcher;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.*;
import br.com.fiap.fiapx.video.core.domain.*;
import br.com.fiap.fiapx.video.core.exception.*;
import br.com.fiap.fiapx.video.core.gateway.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import tools.jackson.databind.json.JsonMapper;
import br.com.fiap.fiapx.video.ProcessingFixtures;
import br.com.fiap.fiapx.video.infrastructure.messaging.ProcessingResultDecoder;
import br.com.fiap.fiapx.video.infrastructure.messaging.ProcessingResultsConsumer;
import software.amazon.awssdk.services.sqs.model.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL and HTTP. AWS/identity remain mocks; this is not evidence of AWS integration. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UploadPostgresIT {
    @TempDir static Path temporary;
    ConfigurableApplicationContext context;
    JdbcTemplate jdbc;
    String base;
    Path publicKey;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    final UUID owner = UUID.randomUUID(), key = UUID.randomUUID();
    String acceptedId;
    final String schema = "it_upload_" + UUID.randomUUID().toString().replace("-", "");
    final String upgradeSchema = schema + "_upgrade";
    boolean schemaCreated;
    boolean upgradeCreated;
    boolean resultsOnly;

    /** Test-only barrier after predicate evaluation, before the production SELECT acquires its row lock. */
    public static class CleanupSnapshotInspector implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static volatile boolean enabled;
        public String inspect(String sql) {
            String predicate="NOT EXISTS (SELECT 1 FROM video_download_leases d WHERE d.video_id=videos.id AND d.valid_until>clock_timestamp())";
            return enabled && sql.contains("FOR UPDATE SKIP LOCKED")
                    ? sql.replace(predicate,"test_cleanup_snapshot_gate("+predicate+")") : sql;
        }
    }

    java.sql.Connection database() throws java.sql.SQLException {
        return DriverManager.getConnection(System.getenv("UPLOAD_TEST_DB_URL"),
                System.getenv("UPLOAD_TEST_DB_USERNAME"), System.getenv("UPLOAD_TEST_DB_PASSWORD"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Boundaries {
        static final Set<UUID> inactive=java.util.concurrent.ConcurrentHashMap.newKeySet();
        @Bean @Primary S3Client testS3() { return mock(S3Client.class); }
        @Bean @Primary SqsClient testSqs() {
            var sqs=mock(SqsClient.class);
            when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build());
            return sqs;
        }
        @Bean @Primary JwtDecoder testJwt() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject(token).claim("ver", 1L).build();
        }
        @Bean @Primary AccountAccessGateway testIdentity() {
            return token -> new AccountAccess(UUID.fromString(token), "USER", !inactive.contains(UUID.fromString(token)), 1L);
        }
    }

    @BeforeAll void start() throws Exception {
        String url = System.getenv("UPLOAD_TEST_DB_URL");
        assertNotNull(url, "Run make integration with the local .env connection");
        assertTrue(url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/.*"),
                "Integration tests require a local PostgreSQL host");
        try (var connection = database(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            schemaCreated = true;
        }
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        publicKey = Files.writeString(temporary.resolve("public.pem"), "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        startApplication();
    }
    Class<?> boundaries() { return Boundaries.class; }
    String bucket() { return "fiapx-media-test"; }
    String awsProfile() { return ""; }
    void startApplication() {
        context = new SpringApplicationBuilder(VideoApplication.class, boundaries()).run(
                "--server.port=0", "--spring.datasource.url=" + System.getenv("UPLOAD_TEST_DB_URL"),
                "--spring.datasource.username=" + System.getenv("UPLOAD_TEST_DB_USERNAME"),
                "--spring.datasource.password=" + System.getenv("UPLOAD_TEST_DB_PASSWORD"),
                "--spring.datasource.hikari.schema=" + schema,
                "--spring.liquibase.default-schema=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--spring.jpa.properties.hibernate.session_factory.statement_inspector=" + CleanupSnapshotInspector.class.getName(),
                "--identity.service-key=" + "x".repeat(32), "--identity.jwt.public-key=" + publicKey.toUri(),
                "--upload.enabled="+!resultsOnly, "--upload.bucket=" + bucket(), "--upload.aws-profile=" + awsProfile(),
                "--upload.publisher-enabled=false", "--upload.cleanup-enabled=false", "--results.enabled="+resultsOnly,
                "--results.queue-url=https://sqs.us-east-1.amazonaws.com/123456789012/events",
                "--upload.temp-directory=" + temporary.resolve("staging"), "--springdoc.api-docs.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }
    @AfterAll void stop() throws Exception {
        if(context != null) context.close();
        // Identifiers are generated by this harness, never taken from .env or application data.
        assertTrue(schema.matches("it_upload_[0-9a-f]{32}"));
        if (schemaCreated || upgradeCreated) {
            try (var connection = database(); var sql = connection.createStatement()) {
                if (upgradeCreated) sql.execute("DROP SCHEMA " + upgradeSchema + " CASCADE");
                if (schemaCreated) sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
    HttpResponse<String> post(UUID account, UUID intention, String name, String data) throws Exception {
        String boundary = "fiapx-upload-test";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: video/mp4\r\n\r\n" + data + "\r\n--" + boundary + "--\r\n";
        return http.send(HttpRequest.newBuilder(URI.create(base + "/videos"))
                .header("Authorization", "Bearer " + account).header("Idempotency-Key", intention.toString())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test @Order(1) void realMultipartAcceptsDurablyAndReplayDoesNotDuplicate() throws Exception {
        var first = post(owner, key, "sample.mp4", "abc"); assertEquals(202, first.statusCode(), first.body());
        acceptedId = json.readTree(first.body()).get("id").asText();
        assertEquals("QUEUED", json.readTree(first.body()).get("status").asText());
        assertEquals("/videos/" + acceptedId, first.headers().firstValue("Location").orElseThrow());
        var retry = post(owner, key, "sample.mp4", "abc"); assertEquals(first.body(), retry.body());
        assertEquals(409, post(owner, key, "sample.mp4", "different").statusCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from videos where owner_id=?", Integer.class, owner));
        assertEquals(1, jdbc.queryForObject("select count(*) from video_outbox where owner_id=? and published_at is null", Integer.class, owner));
        verify(context.getBean("testS3", S3Client.class), times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        var other = UUID.randomUUID(); assertEquals(202, post(other, key, "sample.mp4", "abc").statusCode());
        var response = http.send(HttpRequest.newBuilder(URI.create(base + "/videos/" + acceptedId)).header("Authorization", "Bearer " + other)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test @Order(2) void outboxFailureRollsBackQueuedTransition() throws Exception {
        UUID rejectedOwner = UUID.randomUUID();
        jdbc.execute("ALTER TABLE video_outbox ADD CONSTRAINT test_reject_owner CHECK(owner_id <> '" + rejectedOwner + "'::uuid)");
        try {
            var result = post(rejectedOwner, UUID.randomUUID(), "sample.mp4", "abc");
            assertEquals(503, result.statusCode(), result.body());
            assertEquals("UPLOADING", jdbc.queryForObject("SELECT status FROM videos WHERE owner_id=?", String.class, rejectedOwner));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM video_outbox WHERE owner_id=?", Integer.class, rejectedOwner));
        } finally { jdbc.execute("ALTER TABLE video_outbox DROP CONSTRAINT test_reject_owner"); }
    }

    @Test @Order(3) void expiredAttemptIsFencedAndOriginalIntentionIsUnique() {
        var gateway = context.getBean(UploadGateway.class); var now = gateway.now();
        UUID id = UUID.randomUUID(), account = UUID.randomUUID(), intention = UUID.randomUUID(), token = UUID.randomUUID();
        var old = new UploadIntent(new Video(id, account, "test.mp4", UploadIntent.objectKey(account, id, token), 3, now.minusSeconds(1)),
                intention, "a".repeat(64), token, now.minusMillis(1), null);
        gateway.reserve(old);
        assertThrows(VideoConflictException.class, () -> gateway.reserve(old));
        UUID next = UUID.randomUUID();
        var replacement = new UploadIntent(new Video(id, account, "test.mp4", UploadIntent.objectKey(account, id, next), 3, old.video().createdAt()),
                intention, old.sha256(), next, now.plusSeconds(300), null);
        assertTrue(gateway.takeOver(old, replacement)); assertFalse(gateway.takeOver(old, replacement));
        assertThrows(UploadException.class, () -> gateway.accept(id, token, "media"));
        assertEquals(next, gateway.accept(id, next, "media").attemptId());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM video_upload_attempts WHERE video_id=?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM video_outbox WHERE video_id=?", Integer.class, id));
    }

    @Test @Order(4) void restartPreservesReceiptAndDispatcherResumesStableEnvelope() throws Exception {
        String before = post(owner, key, "sample.mp4", "abc").body();
        context.close(); startApplication();
        assertEquals(before, post(owner, key, "sample.mp4", "abc").body());
        var sqs = context.getBean("testSqs", SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(new RuntimeException("network failure"));
        var dispatcher = new OutboxDispatcher(context.getBean(SpringOutboxRepository.class), context.getBean(TransactionTemplate.class), sqs,
                "https://sqs.us-east-1.amazonaws.com/000000000000/work");
        dispatcher.dispatch();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM video_outbox WHERE published_at IS NOT NULL", Integer.class));
        jdbc.update("UPDATE video_outbox SET available_at=clock_timestamp()");
        doReturn(null).when(sqs).sendMessage(any(SendMessageRequest.class)); dispatcher.dispatch();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM video_outbox WHERE published_at IS NULL", Integer.class));
        var sent = org.mockito.ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, atLeast(2)).sendMessage(sent.capture());
        var bodies = sent.getAllValues().stream().map(SendMessageRequest::messageBody).toList();
        assertTrue(bodies.stream().distinct().count() < bodies.size(), "Retries preserve the persisted event envelope");
    }

    @Test @Order(3) void concurrentReservationsUseDatabaseUniqueness() throws Exception {
        var gateway = context.getBean(UploadGateway.class);
        UUID account = UUID.randomUUID(), intention = UUID.randomUUID();
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> reserve = () -> {
            var now = gateway.now(); UUID id = UUID.randomUUID(), token = UUID.randomUUID();
            var intent = new UploadIntent(new Video(id, account, "sample.mp4", UploadIntent.objectKey(account, id, token), 3, now),
                    intention, "a".repeat(64), token, now.plusSeconds(300), null);
            start.await();
            try { gateway.reserve(intent); return true; } catch(VideoConflictException conflict) { return false; }
        };
        try(var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(reserve); var second = executor.submit(reserve); start.countDown();
            assertNotEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM videos WHERE owner_id=? AND idempotency_key=?", Integer.class, account, intention));
    }

    @Test @Order(3) void cleanupWaitsForUncommittedAcceptanceBeforeInspectingReference() throws Exception {
        var gateway = context.getBean(UploadGateway.class); var now = gateway.now();
        UUID id = UUID.randomUUID(), account = UUID.randomUUID(), token = UUID.randomUUID();
        String objectKey = UploadIntent.objectKey(account, id, token);
        gateway.reserve(new UploadIntent(new Video(id, account, "sample.mp4", objectKey, 3, now),
                UUID.randomUUID(), "a".repeat(64), token, now.plusSeconds(300), null));
        var storage = mock(S3Client.class);
        when(storage.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                        .contents(software.amazon.awssdk.services.s3.model.S3Object.builder().key(objectKey).lastModified(now.minusSeconds(1000)).build()).build());
        var tx = context.getBean(TransactionTemplate.class);
        var reconciler = new br.com.fiap.fiapx.video.infrastructure.storage.UploadReconciler(context.getBean(SpringVideoRepository.class),
                tx, storage, "media", Clock.systemUTC(), context.getBean(br.com.fiap.fiapx.video.infrastructure.web.UploadFiles.class));
        var updated = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try(var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var accept = executor.submit(() -> tx.executeWithoutResult(status -> {
                gateway.accept(id, token, "media"); updated.countDown();
                try { if(!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }));
            try {
                assertTrue(updated.await(10, java.util.concurrent.TimeUnit.SECONDS));
                var clean = executor.submit(() -> { reconciler.reconcile(); return true; });
                assertThrows(java.util.concurrent.TimeoutException.class, () -> clean.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
                release.countDown(); assertTrue(clean.get(10, java.util.concurrent.TimeUnit.SECONDS));
                accept.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        verify(storage, never()).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test @Order(5) void migrationPreservesLegacyMetadataAndRejectsPartialIntentions() throws Exception {
        try (var connection = database();
             var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + upgradeSchema); upgradeCreated = true;
            sql.execute("SET search_path TO " + upgradeSchema);
            for(var change : List.of("001-create-videos.sql", "002-upload-intentions-outbox.sql", "003-processing-results.sql", "004-processing-reconciliation.sql", "005-download-retention.sql")) {
                try(var source = getClass().getResourceAsStream("/db/changelog/changes/" + change)) {
                    sql.execute(new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                if(change.startsWith("001")) sql.execute("INSERT INTO videos VALUES ('00000000-0000-0000-0000-000000000001',"
                        + "'00000000-0000-0000-0000-000000000002','legacy.mp4','legacy-key',3,'UPLOADING',clock_timestamp())");
            }
            try(var result = sql.executeQuery("SELECT status,idempotency_key FROM videos")) {
                assertTrue(result.next()); assertEquals("UPLOADING", result.getString(1)); assertNull(result.getObject(2));
            }
            assertThrows(java.sql.SQLException.class, () -> sql.execute("UPDATE videos SET status='QUEUED'"));
            assertThrows(java.sql.SQLException.class, () -> sql.execute("UPDATE videos SET idempotency_key='00000000-0000-0000-0000-000000000003'"));
        }
    }

    record Accepted(UUID owner,UUID key,UUID id,UUID correlation,String receipt) {}
    Accepted accepted() throws Exception {
        UUID account=UUID.randomUUID(),intention=UUID.randomUUID();
        var response=post(account,intention,"sample.mp4","abc"); assertEquals(202,response.statusCode(),response.body());
        UUID id=UUID.fromString(json.readTree(response.body()).path("id").asString());
        UUID correlation=UUID.fromString(jdbc.queryForObject("SELECT payload->>'correlationId' FROM video_outbox WHERE video_id=?",String.class,id));
        return new Accepted(account,intention,id,correlation,response.body());
    }
    Map<String,Object> result(Accepted video,String type,long version) {
        return ProcessingFixtures.envelope(type,video.id(),video.owner(),video.correlation(),version);
    }
    ProcessingResultsGateway results() { return context.getBean(ProcessingResultsGateway.class); }
    HttpResponse<String> get(UUID account,String suffix) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base+"/videos"+suffix)).header("Authorization","Bearer "+account).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    int inbox(Accepted a) { return jdbc.queryForObject("SELECT count(*) FROM video_processing_inbox WHERE video_id=?",Integer.class,a.id()); }

    @Test @Order(6) void completedBeforeStartedSurvivesRestartAndPreservesHttpReceiptPrivacyAndExpiredTime() throws Exception {
        var a=accepted(); var completed=result(a,"ProcessingCompleted",2);
        Instant old=Instant.now().minusSeconds(172800).truncatedTo(java.time.temporal.ChronoUnit.MICROS); completed.put("occurredAt",old.toString());
        ProcessingFixtures.payload(completed).put("completedAt",old.toString()); ProcessingFixtures.payload(completed).put("expiresAt",old.plusSeconds(86400).toString());
        assertEquals(ProcessingResultsGateway.Outcome.APPLIED,results().apply(ProcessingFixtures.decode(completed)));
        assertEquals(ProcessingResultsGateway.Outcome.IGNORED,results().apply(ProcessingFixtures.decode(result(a,"ProcessingStarted",1))));
        assertEquals(ProcessingResultsGateway.Outcome.DUPLICATE,results().apply(ProcessingFixtures.decode(completed)));
        assertEquals(2,inbox(a));
        String checksum=jdbc.queryForObject("SELECT md5sum FROM databasechangelog WHERE id='002-upload-intentions-outbox'",String.class);
        context.close(); startApplication();
        assertEquals(checksum,jdbc.queryForObject("SELECT md5sum FROM databasechangelog WHERE id='002-upload-intentions-outbox'",String.class));
        assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM databasechangelog",Integer.class));
        var response=get(a.owner(),"/"+a.id()); assertEquals(200,response.statusCode()); var body=json.readTree(response.body());
        assertEquals("COMPLETED",body.path("status").asString());
        // PostgreSQL timestamptz stores microseconds; timestamps retain the producer's expiration, not receive time.
        assertEquals(old.plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS),Instant.parse(body.path("expiresAt").asString()));
        for (String field:List.of("bucket","objectKey","sha256","result","resultBucket","originalObjectKey")) assertFalse(body.has(field));
        var page=json.readTree(get(a.owner(),"").body()); assertEquals(body,page.path("items").get(0));
        assertEquals(404,get(UUID.randomUUID(),"/"+a.id()).statusCode());
        assertEquals(a.receipt(),post(a.owner(),a.key(),"sample.mp4","abc").body());
    }

    @Test @Order(7) void inactiveOwnerStillReceivesResultsButCannotQueryThemAndFailureIsSanitized() throws Exception {
        var a=accepted(); Boundaries.inactive.add(a.owner());
        try {
            assertEquals(ProcessingResultsGateway.Outcome.APPLIED,results().apply(ProcessingFixtures.decode(result(a,"ProcessingStarted",1))));
            assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM videos WHERE id=?",String.class,a.id()));
            results().apply(ProcessingFixtures.decode(result(a,"ProcessingFailed",2)));
            assertEquals(403,get(a.owner(),"/"+a.id()).statusCode()); assertEquals(403,get(a.owner(),"").statusCode());
        } finally { Boundaries.inactive.remove(a.owner()); }
        var body=json.readTree(get(a.owner(),"/"+a.id()).body()); assertEquals("FAILED",body.path("status").asString());
        assertEquals("INVALID_MEDIA",body.path("failureCode").asString()); assertTrue(body.has("failedAt"));
        assertFalse(body.has("expiresAt")); assertFalse(body.has("completedAt"));
        assertEquals(a.receipt(),post(a.owner(),a.key(),"sample.mp4","abc").body());
    }

    @Test @Order(8) void inboxRollbackPreventsAckAndFailedAckThenReplayPreserveCommittedResult() throws Exception {
        var a=accepted(); var failed=result(a,"ProcessingFailed",2); var sqs=mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().messageId("delivery").receiptHandle("latest").body(json.writeValueAsString(failed)).build()).build());
        jdbc.execute("ALTER TABLE video_processing_inbox ADD CONSTRAINT reject_result_test CHECK(video_id<>'"+a.id()+"'::uuid)");
        try (var consumer=new ProcessingResultsConsumer(sqs,"https://sqs.us-east-1.amazonaws.com/123456789012/events",
                new ProcessingResultDecoder(json,ProcessingFixtures.BUCKET),results())) {
            consumer.poll(); assertEquals(0,inbox(a));
            assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM videos WHERE id=?",String.class,a.id()));
            verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
            jdbc.execute("ALTER TABLE video_processing_inbox DROP CONSTRAINT reject_result_test");
            when(sqs.deleteMessage(any(DeleteMessageRequest.class))).thenAnswer(call->{
                assertEquals(1,inbox(a)); assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM videos WHERE id=?",String.class,a.id()));
                throw new IllegalStateException("uncertain ACK");
            });
            consumer.poll(); consumer.poll(); verify(sqs,times(2)).deleteMessage(any(DeleteMessageRequest.class));
            assertEquals(1,inbox(a));
        } finally { jdbc.execute("ALTER TABLE video_processing_inbox DROP CONSTRAINT IF EXISTS reject_result_test"); }
    }

    @Test @Order(9) void simultaneousDuplicateHasOneEffectAndConflictsNeverOverwriteTerminal() throws Exception {
        var a=accepted(); var completed=result(a,"ProcessingCompleted",2); var event=ProcessingFixtures.decode(completed);
        try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<ProcessingResultsGateway.Outcome> action=()->{start.await(); return results().apply(event);};
            var first=pool.submit(action); var second=pool.submit(action); start.countDown();
            assertEquals(Set.of(ProcessingResultsGateway.Outcome.APPLIED,ProcessingResultsGateway.Outcome.DUPLICATE),
                    Set.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS)));
        }
        var different=result(a,"ProcessingFailed",3); assertThrows(IllegalArgumentException.class,()->results().apply(ProcessingFixtures.decode(different)));
        ProcessingFixtures.payload(completed).put("sizeBytes",101);
        assertThrows(IllegalArgumentException.class,()->results().apply(ProcessingFixtures.decode(completed)));
        var wrongOwner=result(a,"ProcessingStarted",1); wrongOwner.put("ownerId",UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class,()->results().apply(ProcessingFixtures.decode(wrongOwner)));
        var correlation=result(a,"ProcessingStarted",1); correlation.put("correlationId",UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class,()->results().apply(ProcessingFixtures.decode(correlation)));
        assertEquals(1,inbox(a)); assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM videos WHERE id=?",String.class,a.id()));
        assertEquals(100,jdbc.queryForObject("SELECT result_size_bytes FROM videos WHERE id=?",Long.class,a.id()));
    }

    @Test @Order(10) void oldVersionAndCrossVideoEventCollisionAreRecordedOrRolledBackAtomically() throws Exception {
        var a=accepted(); var newest=result(a,"ProcessingStarted",3); results().apply(ProcessingFixtures.decode(newest));
        assertEquals(ProcessingResultsGateway.Outcome.IGNORED,results().apply(ProcessingFixtures.decode(result(a,"ProcessingStarted",1))));
        assertEquals(3,jdbc.queryForObject("SELECT processing_version FROM videos WHERE id=?",Long.class,a.id()));
        assertThrows(IllegalArgumentException.class,()->results().apply(ProcessingFixtures.decode(result(a,"ProcessingStarted",3))));
        var b=accepted(); var other=result(b,"ProcessingFailed",2); other.put("eventId",newest.get("eventId"));
        assertThrows(IllegalArgumentException.class,()->results().apply(ProcessingFixtures.decode(other)));
        assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM videos WHERE id=?",String.class,b.id())); assertEquals(0,inbox(b));
    }

    br.com.fiap.fiapx.video.infrastructure.messaging.ProcessingReconciler reconciler() {
        return new br.com.fiap.fiapx.video.infrastructure.config.BeanConfig().processingReconciler(
                context.getBean(SpringOutboxRepository.class), context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    }

    @Test @Order(11) void missingTransportIsRecoveredOnceAcrossReplicasWithOriginalEnvelope() throws Exception {
        var a=accepted();
        String original=jdbc.queryForObject("SELECT payload::text FROM video_outbox WHERE video_id=?",String.class,a.id());
        jdbc.update("UPDATE video_outbox SET published_at=clock_timestamp()-interval '7 hours' WHERE video_id=?",a.id());
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> scan=()->{start.await(); return reconciler().reconcile();};
            var first=pool.submit(scan); var second=pool.submit(scan); start.countDown();
            assertEquals(1, first.get(10,java.util.concurrent.TimeUnit.SECONDS)+second.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT reconciliation_count FROM video_outbox WHERE video_id=?",Integer.class,a.id()));
        assertEquals(0,reconciler().reconcile());
        assertEquals(original,jdbc.queryForObject("SELECT payload::text FROM video_outbox WHERE video_id=?",String.class,a.id()));
        var sqs=mock(SqsClient.class);
        new OutboxDispatcher(context.getBean(SpringOutboxRepository.class),context.getBean(TransactionTemplate.class),sqs,
                "https://sqs.us-east-1.amazonaws.com/000000000000/work").dispatch();
        var sent=org.mockito.ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs,atLeastOnce()).sendMessage(sent.capture());
        assertTrue(sent.getAllValues().stream().anyMatch(r->json.readTree(r.messageBody()).equals(json.readTree(original))));
        assertEquals(0,reconciler().reconcile());
        context.close(); startApplication();
        assertEquals(1,jdbc.queryForObject("SELECT reconciliation_count FROM video_outbox WHERE video_id=?",Integer.class,a.id()));
        results().apply(ProcessingFixtures.decode(result(a,"ProcessingStarted",1)));
        jdbc.update("UPDATE video_outbox SET published_at=clock_timestamp()-interval '7 hours' WHERE video_id=?",a.id());
        assertEquals(1,reconciler().reconcile());
        assertEquals(2,jdbc.queryForObject("SELECT reconciliation_count FROM video_outbox WHERE video_id=?",Integer.class,a.id()));
    }

    @Test @Order(12) void reconciliationRollbackAndTerminalStatesPreservePublication() throws Exception {
        var a=accepted();
        jdbc.update("UPDATE video_outbox SET published_at=clock_timestamp()-interval '7 hours' WHERE video_id=?",a.id());
        jdbc.execute("ALTER TABLE video_outbox ADD CONSTRAINT reject_reconcile_test CHECK(video_id<>'"+a.id()+"'::uuid OR reconciliation_count=0)");
        try {
            assertThrows(RuntimeException.class,()->reconciler().reconcile());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM video_outbox WHERE video_id=? AND published_at IS NOT NULL",Integer.class,a.id()));
            assertEquals(0,jdbc.queryForObject("SELECT reconciliation_count FROM video_outbox WHERE video_id=?",Integer.class,a.id()));
        } finally { jdbc.execute("ALTER TABLE video_outbox DROP CONSTRAINT reject_reconcile_test"); }
        results().apply(ProcessingFixtures.decode(result(a,"ProcessingFailed",2)));
        var b=accepted(); results().apply(ProcessingFixtures.decode(result(b,"ProcessingCompleted",2)));
        jdbc.update("UPDATE video_outbox SET published_at=clock_timestamp()-interval '7 hours' WHERE video_id=?",b.id());
        assertEquals(0,reconciler().reconcile());
        assertEquals(0,jdbc.queryForObject("SELECT sum(reconciliation_count) FROM video_outbox WHERE video_id IN (?,?)",Long.class,a.id(),b.id()));
    }

    @Test @Order(13) void resultConsumerCanStartWithUploadsDisabledAndOnlyMockedTransport() {
        context.close(); resultsOnly=true; startApplication();
        assertNotNull(context.getBean(ProcessingResultsConsumer.class));
        assertTrue(context.getBeansOfType(UploadGateway.class).isEmpty());
        assertNotNull(context.getBean(SqsClient.class));
        assertEquals(3,context.getBean(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class)
                .getScheduledThreadPoolExecutor().getCorePoolSize());
    }

    Accepted downloadVideo() throws Exception {
        if(resultsOnly) { context.close(); resultsOnly=false; startApplication(); }
        var video=accepted(); var event=result(video,"ProcessingCompleted",2);
        Instant completed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        event.put("occurredAt",completed.toString());
        ProcessingFixtures.payload(event).put("completedAt",completed.toString());
        ProcessingFixtures.payload(event).put("expiresAt",completed.plusSeconds(86400).toString());
        results().apply(ProcessingFixtures.decode(event)); return video;
    }
    DownloadGateway downloads() { return context.getBean(DownloadGateway.class); }
    void expireDownload(Accepted video) {
        jdbc.update("UPDATE videos SET completed_at=statement_timestamp()-interval '25 hours',expires_at=statement_timestamp()-interval '1 hour' WHERE id=?",video.id());
    }
    Optional<DownloadGateway.Cleanup> cleanupFor(Accepted video) {
        return downloads().claimExpired(100,Duration.ofSeconds(120)).stream().filter(c->c.artifact().videoId().equals(video.id())).findFirst();
    }

    @Test @Order(14) void downloadLeasesProtectExpiredResultUntilBothTransfersRelease() throws Exception {
        var video=downloadVideo(); var gateway=downloads();
        assertThrows(VideoNotFoundException.class,()->gateway.reserve(video.id(),UUID.randomUUID(),Duration.ofSeconds(120),Duration.ofSeconds(1800)));
        var first=gateway.reserve(video.id(),video.owner(),Duration.ofSeconds(120),Duration.ofSeconds(1800));
        var second=gateway.reserve(video.id(),video.owner(),Duration.ofSeconds(120),Duration.ofSeconds(1800));
        assertNotEquals(first.token(),second.token()); assertEquals(video.owner(),first.artifact().ownerId());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM video_download_leases WHERE video_id=?",Integer.class,video.id()));
        expireDownload(video);
        assertEquals(DownloadException.Reason.EXPIRED,assertThrows(DownloadException.class,()->gateway.reserve(video.id(),video.owner(),Duration.ofSeconds(120),Duration.ofSeconds(1800))).reason());
        assertTrue(cleanupFor(video).isEmpty()); assertTrue(gateway.renew(video.id(),first.token(),Duration.ofSeconds(120)).isPresent());
        gateway.release(video.id(),first.token()); gateway.release(video.id(),first.token());
        assertTrue(cleanupFor(video).isEmpty()); gateway.release(video.id(),second.token());
        var clean=cleanupFor(video).orElseThrow();
        assertTrue(gateway.renew(video.id(),second.token(),Duration.ofSeconds(120)).isEmpty());
        assertFalse(gateway.deleted(video.id(),UUID.randomUUID())); assertTrue(gateway.deleted(video.id(),clean.token()));
        assertFalse(gateway.deleted(video.id(),clean.token())); assertTrue(cleanupFor(video).isEmpty());
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM videos WHERE id=?",String.class,video.id()));
        assertNotNull(jdbc.queryForObject("SELECT result_object_key FROM videos WHERE id=?",String.class,video.id()));
        assertEquals(video.receipt(),post(video.owner(),video.key(),"sample.mp4","abc").body());
        assertEquals(1,inbox(video));
    }

    @Test @Order(15) void cleanupRetryAndCrashSurviveRestartAndFencePreviousOwner() throws Exception {
        var video=downloadVideo(); expireDownload(video); var initial=cleanupFor(video).orElseThrow();
        context.close(); startApplication();
        assertTrue(cleanupFor(video).isEmpty());
        jdbc.update("UPDATE videos SET result_cleanup_until=clock_timestamp()-interval '1 second' WHERE id=?",video.id());
        var next=cleanupFor(video).orElseThrow(); assertNotEquals(initial.token(),next.token());
        assertFalse(downloads().deleted(video.id(),initial.token()));
        assertFalse(downloads().retryCleanup(video.id(),initial.token(),Duration.ofSeconds(30)));
        assertTrue(downloads().retryCleanup(video.id(),next.token(),Duration.ofSeconds(30)));
        assertTrue(cleanupFor(video).isEmpty());
        jdbc.update("UPDATE videos SET result_cleanup_available_at=clock_timestamp()-interval '1 second' WHERE id=?",video.id());
        var retried=cleanupFor(video).orElseThrow(); assertTrue(downloads().deleted(video.id(),retried.token()));
        assertNotNull(jdbc.queryForObject("SELECT result_deleted_at FROM videos WHERE id=?",java.sql.Timestamp.class,video.id()));
    }

    @Test @Order(16) void concurrentCleanersClaimOneResultOnce() throws Exception {
        var video=downloadVideo(); expireDownload(video);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Optional<DownloadGateway.Cleanup>> action=()-> { start.await(); return cleanupFor(video); };
            var a=executor.submit(action); var b=executor.submit(action); start.countDown();
            var x=a.get(10,java.util.concurrent.TimeUnit.SECONDS); var y=b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(x.isPresent(),y.isPresent());
            assertTrue(downloads().deleted(video.id(),x.orElseGet(y::orElseThrow).token()));
        }
    }

    @Test @Order(17) void admissionRechecksExpirationAfterWaitingForVideoLock() throws Exception {
        var video=downloadVideo(); var tx=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var locked=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var hold=executor.submit(()->tx.executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM videos WHERE id=? FOR UPDATE",UUID.class,video.id()); locked.countDown();
                try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                expireDownload(video);
            }));
            try {
                assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var attempt=executor.submit(()->assertThrows(DownloadException.class,()->downloads().reserve(video.id(),video.owner(),Duration.ofSeconds(120),Duration.ofSeconds(1800))).reason());
                assertThrows(java.util.concurrent.TimeoutException.class,()->attempt.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));
                release.countDown(); hold.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(DownloadException.Reason.EXPIRED,attempt.get(5,java.util.concurrent.TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM video_download_leases WHERE video_id=?",Integer.class,video.id()));
    }

    @Test @Order(18) void expiredDownloadCannotResurrectAndRenewalCannotExtendDeadline() throws Exception {
        var video=downloadVideo(); var lease=downloads().reserve(video.id(),video.owner(),Duration.ofSeconds(30),Duration.ofSeconds(60));
        assertEquals(lease.deadline(),downloads().renew(video.id(),lease.token(),Duration.ofSeconds(120)).orElseThrow());
        jdbc.update("UPDATE video_download_leases SET created_at=clock_timestamp()-interval '2 minutes',valid_until=clock_timestamp()-interval '1 second' WHERE token=?",lease.token());
        assertTrue(downloads().renew(video.id(),lease.token(),Duration.ofSeconds(120)).isEmpty());
        expireDownload(video); var claim=cleanupFor(video).orElseThrow();
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM video_download_leases WHERE video_id=?",Integer.class,video.id()));
        assertTrue(downloads().deleted(video.id(),claim.token()));
    }

    @Test @Order(19) void reservationRollbackDoesNotLeakAndIndependentCommitSurvivesOuterRollback() throws Exception {
        var video=downloadVideo();
        jdbc.execute("ALTER TABLE video_download_leases ADD CONSTRAINT reject_test_download CHECK(video_id <> '"+video.id()+"'::uuid)");
        try {
            assertEquals(DownloadException.Reason.UNAVAILABLE,assertThrows(DownloadException.class,()->downloads().reserve(video.id(),video.owner(),Duration.ofSeconds(120),Duration.ofSeconds(1800))).reason());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM video_download_leases WHERE video_id=?",Integer.class,video.id()));
        } finally { jdbc.execute("ALTER TABLE video_download_leases DROP CONSTRAINT reject_test_download"); }
        var outer=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var lease=outer.execute(status->{
            var acquired=downloads().reserve(video.id(),video.owner(),Duration.ofSeconds(120),Duration.ofSeconds(1800));
            status.setRollbackOnly(); return acquired;
        });
        context.close(); startApplication();
        assertTrue(downloads().renew(video.id(),lease.token(),Duration.ofSeconds(120)).isPresent());
        downloads().release(video.id(),lease.token());
    }

    @Test @Order(20) void cleanupRechecksLeaseCommittedAfterItsSelectionSnapshot() throws Exception {
        var video=downloadVideo(); UUID token=UUID.randomUUID();
        long barrierKey=UUID.randomUUID().getLeastSignificantBits();
        jdbc.execute("CREATE FUNCTION test_cleanup_snapshot_gate(eligible boolean) RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$ BEGIN IF eligible THEN PERFORM pg_advisory_xact_lock("+barrierKey+"); END IF; RETURN eligible; END $$");
        // Select only this video so the barrier cannot be reached for a historical fixture.
        jdbc.update("UPDATE videos SET result_cleanup_available_at=clock_timestamp()+interval '1 day' WHERE id<>?",video.id());
        jdbc.update("UPDATE videos SET completed_at=statement_timestamp()-interval '24 hours'+interval '2 seconds', expires_at=statement_timestamp()+interval '2 seconds' WHERE id=?",video.id());
        var inserted=new java.util.concurrent.CountDownLatch(1); var commit=new java.util.concurrent.CountDownLatch(1);
        var tx=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var repository=context.getBean(SpringDownloadRepository.class);
        try(var gateConnection=database(); var gate=gateConnection.createStatement();
            var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            gate.execute("SELECT pg_advisory_lock("+barrierKey+")");
            try {
                var reservation=executor.submit(()->tx.executeWithoutResult(status->{
                    var row=repository.lock(video.id()).orElseThrow(); Instant now=repository.now();
                    assertTrue(now.isBefore(row.getExpiresAt()));
                    assertEquals(1,repository.insertLease(token,video.id(),now,now.plusSeconds(120),now.plusSeconds(1800)));
                    inserted.countDown();
                    try { if(!commit.await(8,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Reservation barrier timed out"); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                }));
                assertTrue(inserted.await(5,java.util.concurrent.TimeUnit.SECONDS));
                jdbc.queryForList("SELECT pg_sleep(GREATEST(0,EXTRACT(epoch FROM expires_at-clock_timestamp()))) FROM videos WHERE id=?",video.id());
                CleanupSnapshotInspector.enabled=true;
                var cleanup=executor.submit(()->cleanupFor(video));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event='advisory' AND query LIKE '%test_cleanup_snapshot_gate%')",Boolean.class);
                    if(waiting) break;
                    Thread.sleep(20);
                }
                assertTrue(waiting,"Cleanup must pause after evaluating its old snapshot");
                commit.countDown(); reservation.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM video_download_leases WHERE token=? AND valid_until>clock_timestamp()",Integer.class,token));
                gate.execute("SELECT pg_advisory_unlock("+barrierKey+")");
                assertTrue(cleanup.get(5,java.util.concurrent.TimeUnit.SECONDS).isEmpty(),"A newly committed active transfer must not be claimed for deletion");
                assertNull(jdbc.queryForObject("SELECT result_cleanup_token FROM videos WHERE id=?",UUID.class,video.id()));
                CleanupSnapshotInspector.enabled=false;
                downloads().release(video.id(),token);
                assertTrue(cleanupFor(video).isPresent(),"Cleanup becomes eligible after the transfer releases");
            } finally {
                commit.countDown(); CleanupSnapshotInspector.enabled=false;
                gate.execute("SELECT pg_advisory_unlock("+barrierKey+")");
            }
        }
    }
}
