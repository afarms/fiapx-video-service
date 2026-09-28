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

    java.sql.Connection database() throws java.sql.SQLException {
        return DriverManager.getConnection(System.getenv("UPLOAD_TEST_DB_URL"),
                System.getenv("UPLOAD_TEST_DB_USERNAME"), System.getenv("UPLOAD_TEST_DB_PASSWORD"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Boundaries {
        @Bean @Primary S3Client testS3() { return mock(S3Client.class); }
        @Bean @Primary SqsClient testSqs() { return mock(SqsClient.class); }
        @Bean @Primary JwtDecoder testJwt() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject(token).claim("ver", 1L).build();
        }
        @Bean @Primary AccountAccessGateway testIdentity() {
            return token -> new AccountAccess(UUID.fromString(token), "USER", true, 1L);
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
                "--identity.service-key=" + "x".repeat(32), "--identity.jwt.public-key=" + publicKey.toUri(),
                "--upload.enabled=true", "--upload.bucket=" + bucket(), "--upload.aws-profile=" + awsProfile(),
                "--upload.publisher-enabled=false", "--upload.cleanup-enabled=false",
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
            for(var change : List.of("001-create-videos.sql", "002-upload-intentions-outbox.sql")) {
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
}
