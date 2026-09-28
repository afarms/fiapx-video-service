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
    boolean downloadsEnabled;
    boolean cleanupOnly;

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
        @Bean @Primary br.com.fiap.fiapx.video.infrastructure.storage.DownloadStorage testDownloadStorage(S3Client client) {
            return new br.com.fiap.fiapx.video.infrastructure.storage.S3DownloadStorage(client);
        }
        @Bean @Primary ResultCleanupStorageGateway testCleanupStorage(S3Client client) {
            return new br.com.fiap.fiapx.video.infrastructure.storage.S3ResultCleanupStorage(client);
        }
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
                "--upload.enabled="+(!resultsOnly&&!cleanupOnly), "--upload.bucket=" + bucket(), "--upload.aws-profile=" + awsProfile(),
                "--upload.publisher-enabled=false", "--upload.cleanup-enabled=false", "--results.enabled="+(resultsOnly&&!cleanupOnly),
                "--download.enabled="+(downloadsEnabled&&!cleanupOnly), "--download.heartbeat-seconds=1",
                "--download.cleanup-enabled="+cleanupOnly, "--download.cleanup-delay-ms=1000",
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

    @Test @Order(21) void httpDownloadReturnsWholeResultAndRejectsUnauthorizedRequestsBeforeS3() throws Exception {
        context.close(); downloadsEnabled=true; startApplication();
        var video=downloadVideo();
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=");
        var zipBytes=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("frames/frame-000001.png")); zip.write(png); zip.closeEntry();
        }
        byte[] bytes=zipBytes.toByteArray();
        jdbc.update("UPDATE videos SET result_size_bytes=? WHERE id=?",bytes.length,video.id());
        var client=context.getBean("testS3",S3Client.class);
        var aborted=new java.util.concurrent.atomic.AtomicInteger();
        when(client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenAnswer(call ->
                new software.amazon.awssdk.core.ResponseInputStream<>(
                        software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().contentLength((long)bytes.length).build(),
                        software.amazon.awssdk.http.AbortableInputStream.create(new java.io.ByteArrayInputStream(bytes),aborted::incrementAndGet)));
        String path=base+"/videos/"+video.id()+"/download";
        var response=http.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization","Bearer "+video.owner())
                .header("Range","bytes=50-").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,response.statusCode()); assertArrayEquals(bytes,response.body());
        assertEquals(Integer.toString(bytes.length),response.headers().firstValue("Content-Length").orElseThrow());
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.body()))) {
            assertEquals("frames/frame-000001.png",zip.getNextEntry().getName()); assertArrayEquals(png,zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
        assertEquals("application/zip",response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("none",response.headers().firstValue("Accept-Ranges").orElseThrow());
        assertEquals("private, no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("attachment; filename=\"frames-"+video.id()+".zip\"",response.headers().firstValue("Content-Disposition").orElseThrow());
        awaitNoDownloadLease(video); assertTrue(aborted.get()>0);
        clearInvocations(client);
        assertEquals(401,http.send(HttpRequest.newBuilder(URI.create(path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404,downloadHttp(UUID.randomUUID(),video.id()).statusCode());
        assertEquals(404,downloadHttp(video.owner(),UUID.randomUUID()).statusCode());
        Boundaries.inactive.add(video.owner());
        try { assertEquals(403,downloadHttp(video.owner(),video.id()).statusCode()); }
        finally { Boundaries.inactive.remove(video.owner()); }
        var pending=accepted(); assertEquals(409,downloadHttp(pending.owner(),pending.id()).statusCode());
        expireDownload(video); assertEquals(410,downloadHttp(video.owner(),video.id()).statusCode());
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM videos WHERE id=?",String.class,video.id()));
        verify(client,never()).getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class));
    }

    HttpResponse<byte[]> downloadHttp(UUID account,UUID video) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base+"/videos/"+video+"/download"))
                .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+account).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    void awaitNoDownloadLease(Accepted video) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("SELECT count(*) FROM video_download_leases WHERE video_id=?",Integer.class,video.id())==0) return;
            Thread.sleep(20);
        }
        fail("Download lease was not released");
    }

    @Test @Order(22) void missingS3ResultReturnsUnavailableAndReleasesLease() throws Exception {
        var video=downloadVideo(); var client=context.getBean("testS3",S3Client.class);
        when(client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder().message("private/key").build());
        var response=downloadHttp(video.owner(),video.id()); assertEquals(503,response.statusCode());
        assertFalse(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8).contains("private/key"));
        awaitNoDownloadLease(video);
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM videos WHERE id=?",String.class,video.id()));
    }

    @Test @Order(23) void admittedHttpTransferRenewsAfterExpiryAndBlocksCleanupUntilFinished() throws Exception {
        var video=downloadVideo(); var client=context.getBean("testS3",S3Client.class);
        var opened=new java.util.concurrent.CountDownLatch(1); var unblock=new java.util.concurrent.CountDownLatch(1);
        var input=new java.io.ByteArrayInputStream(new byte[100]) {
            @Override public synchronized int read(byte[] bytes,int offset,int length) {
                opened.countDown();
                try { if(!unblock.await(8,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Read barrier timed out"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return super.read(bytes,offset,length);
            }
        };
        when(client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(
                new software.amazon.awssdk.core.ResponseInputStream<>(
                        software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().contentLength(100L).build(),
                        software.amazon.awssdk.http.AbortableInputStream.create(input,unblock::countDown)));
        var future=http.sendAsync(HttpRequest.newBuilder(URI.create(base+"/videos/"+video.id()+"/download"))
                .header("Authorization","Bearer "+video.owner()).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        try {
            assertTrue(opened.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var initial=jdbc.queryForObject("SELECT valid_until FROM video_download_leases WHERE video_id=?",java.sql.Timestamp.class,video.id());
            expireDownload(video); assertTrue(cleanupFor(video).isEmpty());
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5); boolean renewed=false;
            while(System.nanoTime()<deadline) {
                renewed=jdbc.queryForObject("SELECT valid_until>? FROM video_download_leases WHERE video_id=?",Boolean.class,initial,video.id());
                if(renewed) break; Thread.sleep(20);
            }
            assertTrue(renewed,"HTTP transfer must renew its lease after artifact expiration");
            assertTrue(cleanupFor(video).isEmpty());
        } finally { unblock.countDown(); }
        assertEquals(200,future.get(5,java.util.concurrent.TimeUnit.SECONDS).statusCode());
        awaitNoDownloadLease(video); assertTrue(cleanupFor(video).isPresent());
        assertEquals(410,downloadHttp(video.owner(),video.id()).statusCode());
    }

    @Test @Order(24) void realClientDisconnectAbortsS3WithoutReadingWholeLargeResult() throws Exception {
        var video=downloadVideo(); long size=100_000_000L;
        jdbc.update("UPDATE videos SET result_size_bytes=? WHERE id=?",size,video.id());
        var read=new java.util.concurrent.atomic.AtomicLong(); var aborted=new java.util.concurrent.atomic.AtomicBoolean();
        var input=new java.io.InputStream() {
            public int read() { throw new java.lang.UnsupportedOperationException(); }
            public int read(byte[] buffer,int offset,int length) throws java.io.IOException {
                if(aborted.get()) throw new java.io.IOException("Aborted");
                assertTrue(length<=65536); long remaining=size-read.get(); if(remaining==0) return -1;
                int count=(int)Math.min(length,remaining); Arrays.fill(buffer,offset,offset+count,(byte)7); read.addAndGet(count); return count;
            }
        };
        var client=context.getBean("testS3",S3Client.class);
        when(client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(
                new software.amazon.awssdk.core.ResponseInputStream<>(
                        software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().contentLength(size).build(),
                        software.amazon.awssdk.http.AbortableInputStream.create(input,()->aborted.set(true))));
        try(var socket=new java.net.Socket("127.0.0.1",URI.create(base).getPort())) {
            socket.setSoTimeout(5000); socket.setReceiveBufferSize(4096);
            socket.getOutputStream().write(("GET /videos/"+video.id()+"/download HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+video.owner()+"\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(4096,socket.getInputStream().readNBytes(4096).length);
            socket.setSoLinger(true,0);
        }
        awaitNoDownloadLease(video); assertTrue(aborted.get()); assertTrue(read.get()<size);
    }

    @Test @Order(25) void failedUpstreamAfterHeadersTerminatesIncompleteHttpBody() throws Exception {
        var video=downloadVideo(); long size=1_000_000;
        jdbc.update("UPDATE videos SET result_size_bytes=? WHERE id=?",size,video.id());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var input=new java.io.InputStream() {
            public int read() { throw new java.lang.UnsupportedOperationException(); }
            public int read(byte[] bytes,int offset,int length) throws java.io.IOException {
                if(calls.incrementAndGet()>1) throw new java.io.IOException("private upstream failure");
                Arrays.fill(bytes,offset,offset+length,(byte)9); return length;
            }
        };
        when(context.getBean("testS3",S3Client.class).getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(
                new software.amazon.awssdk.core.ResponseInputStream<>(
                        software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().contentLength(size).build(),
                        software.amazon.awssdk.http.AbortableInputStream.create(input)));
        var future=http.sendAsync(HttpRequest.newBuilder(URI.create(base+"/videos/"+video.id()+"/download"))
                .header("Authorization","Bearer "+video.owner()).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        try {
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class,failure.getCause());
            awaitNoDownloadLease(video);
        } finally { future.cancel(true); }
    }

    br.com.fiap.fiapx.video.core.usecase.CleanupExpiredResultsUseCase cleaner(DownloadGateway gateway) {
        return new br.com.fiap.fiapx.video.core.usecase.CleanupExpiredResultsUseCase(gateway,
                context.getBean("testCleanupStorage",ResultCleanupStorageGateway.class),Duration.ofSeconds(120),Duration.ofSeconds(300),1,System::nanoTime);
    }
    void onlyCleanupCandidate(Accepted video) {
        jdbc.update("UPDATE videos SET result_cleanup_available_at=clock_timestamp()+interval '1 day' WHERE id<>?",video.id());
    }
    @Test @Order(26) void cleanupWaitsForDownloadThenRetriesFailureAcrossRestartWithoutLosingHistory() throws Exception {
        var video=downloadVideo(); onlyCleanupCandidate(video);
        var lease=downloads().reserve(video.id(),video.owner(),Duration.ofSeconds(120),Duration.ofSeconds(1800)); expireDownload(video);
        var history=jdbc.queryForMap("SELECT status,completed_at,expires_at,result_bucket,result_object_key,result_size_bytes,result_sha256 FROM videos WHERE id=?",video.id());
        var client=context.getBean("testS3",S3Client.class);
        assertEquals(0,cleaner(downloads()).execute());
        verify(client,never()).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
        downloads().release(video.id(),lease.token());
        when(client.deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(503).build());
        assertEquals(0,cleaner(downloads()).execute());
        assertTrue(jdbc.queryForObject("SELECT result_deleted_at IS NULL AND result_cleanup_token IS NULL AND result_cleanup_available_at>clock_timestamp() FROM videos WHERE id=?",Boolean.class,video.id()));
        assertEquals(0,cleaner(downloads()).execute());
        context.close(); startApplication();
        jdbc.update("UPDATE videos SET result_cleanup_available_at=clock_timestamp()-interval '1 second' WHERE id=?",video.id());
        assertEquals(1,cleaner(downloads()).execute()); assertEquals(0,cleaner(downloads()).execute());
        assertNotNull(jdbc.queryForObject("SELECT result_deleted_at FROM videos WHERE id=?",java.sql.Timestamp.class,video.id()));
        assertEquals(history,jdbc.queryForMap("SELECT status,completed_at,expires_at,result_bucket,result_object_key,result_size_bytes,result_sha256 FROM videos WHERE id=?",video.id()));
    }
    @Test @Order(27) void crashAfterDeleteRepeatsAbsentObjectAndFencesOldClaim() throws Exception {
        var video=downloadVideo(); expireDownload(video); onlyCleanupCandidate(video);
        clearInvocations(context.getBean("testS3",S3Client.class));
        var failing=spy(downloads());
        doThrow(new IllegalStateException("database unavailable after S3 deletion")).when(failing).deleted(any(),any());
        doThrow(new IllegalStateException("database unavailable")).when(failing).retryCleanup(any(),any(),any());
        assertThrows(IllegalStateException.class,()->cleaner(failing).execute());
        var token=jdbc.queryForObject("SELECT result_cleanup_token FROM videos WHERE id=?",UUID.class,video.id()); assertNotNull(token);
        verify(context.getBean("testS3",S3Client.class)).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
        context.close(); startApplication();
        jdbc.update("UPDATE videos SET result_cleanup_until=clock_timestamp()-interval '1 second' WHERE id=?",video.id());
        when(context.getBean("testS3",S3Client.class).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder().build());
        assertEquals(1,cleaner(downloads()).execute()); assertFalse(downloads().deleted(video.id(),token));
    }
    @Test @Order(28) void simultaneousCleanersHaveOneDeleteOutsideDatabaseTransaction() throws Exception {
        var video=downloadVideo(); expireDownload(video); onlyCleanupCandidate(video);
        var deleting=new java.util.concurrent.CountDownLatch(1); var unblock=new java.util.concurrent.CountDownLatch(1);
        var client=context.getBean("testS3",S3Client.class); reset(client);
        when(client.deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class))).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            deleting.countDown(); assertTrue(unblock.await(5,java.util.concurrent.TimeUnit.SECONDS));
            return software.amazon.awssdk.services.s3.model.DeleteObjectResponse.builder().build();
        });
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->cleaner(downloads()).execute());
            try { assertTrue(deleting.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0,cleaner(downloads()).execute()); }
            finally { unblock.countDown(); }
            assertEquals(1,first.get(5,java.util.concurrent.TimeUnit.SECONDS));
        }
        verify(client).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }
    @Test @Order(29) void cleanupSchedulerRunsWithUploadDownloadAndConsumersDisabled() throws Exception {
        var video=downloadVideo(); expireDownload(video); onlyCleanupCandidate(video);
        context.close(); cleanupOnly=true; startApplication();
        assertTrue(context.getBeansOfType(UploadGateway.class).isEmpty());
        assertTrue(context.getBeansOfType(br.com.fiap.fiapx.video.infrastructure.web.DownloadTransfers.class).isEmpty());
        assertTrue(context.getBeansOfType(ProcessingResultsConsumer.class).isEmpty());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5); boolean deleted=false;
        while(System.nanoTime()<deadline) {
            deleted=jdbc.queryForObject("SELECT result_deleted_at IS NOT NULL FROM videos WHERE id=?",Boolean.class,video.id());
            if(deleted) break; Thread.sleep(20);
        }
        assertTrue(deleted); verify(context.getBean("testS3",S3Client.class)).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
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
