package br.com.fiap.fiapx.video.infrastructure.integration;

import br.com.fiap.fiapx.video.core.domain.AccountAccess;
import br.com.fiap.fiapx.video.core.gateway.AccountAccessGateway;
import br.com.fiap.fiapx.video.infrastructure.config.BeanConfig;
import br.com.fiap.fiapx.video.infrastructure.messaging.OutboxDispatcher;
import br.com.fiap.fiapx.video.infrastructure.persistence.repository.*;
import br.com.fiap.fiapx.video.infrastructure.storage.UploadReconciler;
import br.com.fiap.fiapx.video.infrastructure.web.UploadFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sts.StsClient;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in remote writes. Real PostgreSQL/HTTP/S3/SQS; identity is a controlled test boundary. */
class UploadAwsIT {
    @TempDir Path temporary;
    static final String BUCKET = "fiapx-media-files";
    static final String PROFILE = "fiapx-video-local";

    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityBoundary {
        @Bean @Primary JwtDecoder testJwt() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256").subject(token).claim("ver", 1L).build();
        }
        @Bean @Primary AccountAccessGateway testIdentity() {
            return token -> new AccountAccess(UUID.fromString(token), "USER", true, 1L);
        }
    }

    @Test void durableUploadRecoveryAndStableSqsRetryAgainstAws() throws Exception {
        assertEquals("true", System.getenv("UPLOAD_AWS_TEST_APPROVED"), "Explicit AWS write opt-in is required");
        var fixture = new UploadPostgresIT() {
            @Override Class<?> boundaries() { return IdentityBoundary.class; }
            @Override String bucket() { return BUCKET; }
            @Override String awsProfile() { return PROFILE; }
        };
        UploadPostgresIT.temporary = temporary;
        String prefix = "originals/" + fixture.owner + "/";
        var config = new BeanConfig();
        try (var credentials = ProfileCredentialsProvider.create(PROFILE);
             var sts = StsClient.builder().region(Region.US_EAST_1).credentialsProvider(credentials).build();
             var s3 = config.uploadS3(credentials, "us-east-1");
             var sqs = config.uploadSqs(credentials, "us-east-1")) {
            var identity = sts.getCallerIdentity();
            assertTrue(identity.arn().contains(":assumed-role/fiapx-video-local/"), "Unexpected AWS role");
            String queueUrl = "https://sqs.us-east-1.amazonaws.com/" + identity.account() + "/fiapx-processing-work";
            assertTrue(objects(s3, prefix).isEmpty(), "Test prefix must be new");
            try {
                fixture.start();
                var first = fixture.post(fixture.owner, fixture.key, "aws-smoke.mp4", "abc");
                assertEquals(202, first.statusCode());
                var firstId = UUID.fromString(fixture.json.readTree(first.body()).get("id").asText());
                assertEquals(first.body(), fixture.post(fixture.owner, fixture.key, "aws-smoke.mp4", "abc").body());
                assertEquals(1, objects(s3, prefix).size());
                String acceptedKey = fixture.jdbc.queryForObject("SELECT original_object_key FROM videos WHERE id=?", String.class, firstId);
                var original = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(acceptedKey)
                        .checksumMode(ChecksumMode.ENABLED).build());
                assertEquals("abc", original.asUtf8String());
                assertNotNull(original.response().checksumSHA256());

                // Inject SQL failure after a successful S3 PUT, then recover the same intention after restart.
                UUID recoveryKey = UUID.randomUUID();
                fixture.jdbc.execute("ALTER TABLE video_outbox ADD CONSTRAINT test_fail_accept CHECK(false) NOT VALID");
                try { assertEquals(503, fixture.post(fixture.owner, recoveryKey, "recovery.mp4", "abc").statusCode()); }
                finally { fixture.jdbc.execute("ALTER TABLE video_outbox DROP CONSTRAINT test_fail_accept"); }
                assertEquals(2, objects(s3, prefix).size());
                assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM video_outbox", Integer.class));
                fixture.jdbc.update("UPDATE videos SET created_at=clock_timestamp()-interval '2 seconds',"
                        + "upload_lease_until=clock_timestamp()-interval '1 second' WHERE idempotency_key=?", recoveryKey);
                fixture.context.close(); fixture.startApplication();
                assertEquals(first.body(), fixture.post(fixture.owner, fixture.key, "aws-smoke.mp4", "abc").body());
                var recovered = fixture.post(fixture.owner, recoveryKey, "recovery.mp4", "abc");
                assertEquals(202, recovered.statusCode());
                assertEquals(3, objects(s3, prefix).size());
                assertEquals(2, fixture.jdbc.queryForObject("SELECT count(*) FROM videos", Integer.class));

                // Real S3 list/delete, restricted to this test owner. Advance only the cleanup clock.
                var scoped = mock(S3Client.class);
                when(scoped.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(call -> {
                    ListObjectsV2Request request = call.getArgument(0);
                    return s3.listObjectsV2(request.toBuilder().prefix(prefix).build());
                });
                when(scoped.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
                    DeleteObjectRequest request = call.getArgument(0);
                    assertTrue(request.key().startsWith(prefix));
                    return s3.deleteObject(request);
                });
                new UploadReconciler(fixture.context.getBean(SpringVideoRepository.class),
                        fixture.context.getBean(TransactionTemplate.class), scoped, BUCKET,
                        Clock.offset(Clock.systemUTC(), Duration.ofMinutes(20)), fixture.context.getBean(UploadFiles.class)).reconcile();
                assertEquals(2, objects(s3, prefix).size(), "Cleanup removes only the abandoned attempt");
                assertTrue(objects(s3, prefix).stream().anyMatch(object -> object.key().equals(acceptedKey)));

                // Publish only the first video's event. Simulate a lost acknowledgement after AWS accepts it.
                fixture.jdbc.update("UPDATE video_outbox SET available_at=clock_timestamp()+interval '1 day' WHERE video_id<>?", firstId);
                var transport = mock(SqsClient.class);
                var sent = new ArrayList<String>();
                when(transport.sendMessage(any(SendMessageRequest.class))).thenAnswer(call -> {
                    assertTrue(sent.size() < 2, "Maximum two physical SQS sends");
                    SendMessageRequest request = call.getArgument(0);
                    sent.add(request.messageBody());
                    var response = sqs.sendMessage(request);
                    assertNotNull(response.messageId());
                    if (sent.size() == 1) throw new IllegalStateException("Simulated lost acknowledgement after AWS success");
                    return response;
                });
                var dispatcher = new OutboxDispatcher(fixture.context.getBean(SpringOutboxRepository.class),
                        fixture.context.getBean(TransactionTemplate.class), transport, queueUrl);
                dispatcher.dispatch();
                assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM video_outbox WHERE published_at IS NOT NULL", Integer.class));
                fixture.jdbc.update("UPDATE video_outbox SET available_at=clock_timestamp() WHERE video_id=?", firstId);
                dispatcher.dispatch();
                assertEquals(2, sent.size()); assertEquals(sent.get(0), sent.get(1));
                assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM video_outbox WHERE published_at IS NOT NULL", Integer.class));
                System.out.println("AWS upload verified: real S3 recovery, preserved accepted originals, two SQS acknowledgements with identical event body.");
            } finally {
                try {
                    for (var object : objects(s3, prefix)) {
                        assertTrue(object.key().startsWith(prefix));
                        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(object.key()).build());
                    }
                    assertTrue(objects(s3, prefix).isEmpty());
                } finally { fixture.stop(); }
            }
        }
    }

    private List<S3Object> objects(S3Client client, String prefix) {
        var response = client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).maxKeys(4).build());
        assertFalse(Boolean.TRUE.equals(response.isTruncated()), "Test must never create more than three objects");
        assertTrue(response.contents().size() <= 3);
        return response.contents();
    }
}
