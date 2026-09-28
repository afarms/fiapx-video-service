package br.com.fiap.fiapx.video.infrastructure.storage;

import br.com.fiap.fiapx.video.infrastructure.persistence.repository.SpringVideoRepository;
import br.com.fiap.fiapx.video.infrastructure.web.UploadFiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.IOException;
import java.time.Clock;

public class UploadReconciler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UploadReconciler.class);
    private final SpringVideoRepository repository;
    private final TransactionTemplate transactions;
    private final S3Client client;
    private final String bucket;
    private final Clock clock;
    private final UploadFiles files;
    private String continuation;

    public UploadReconciler(SpringVideoRepository repository, TransactionTemplate transactions, S3Client client,
                            String bucket, Clock clock, UploadFiles files) {
        this.repository = repository; this.transactions = transactions; this.client = client;
        this.bucket = bucket; this.clock = clock; this.files = files;
    }

    @Scheduled(fixedDelayString = "${upload.cleanup-delay-ms:60000}")
    public void reconcile() throws IOException {
        files.cleanStaleFiles();
        var page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix("originals/")
                .maxKeys(100).continuationToken(continuation).build());
        for (var object : page.contents()) {
            if (object.lastModified().isAfter(clock.instant().minusSeconds(900))) continue;
            try {
                transactions.executeWithoutResult(tx -> {
                    // Lock serializes cleanup with acceptance; an uncommitted accept must not lose its object.
                    var owner = repository.lockByOriginalKey(object.key());
                    if (owner.isPresent()) {
                        var video = owner.get();
                        if ("QUEUED".equals(video.getStatus()) || video.getIdempotencyKey() == null
                                || video.getUploadLeaseUntil().isAfter(repository.databaseTime())) return;
                    }
                    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
                    repository.cleaned(object.key());
                });
            } catch (RuntimeException failure) {
                LOG.warn("Original cleanup deferred; a later scan will retry");
            }
        }
        // Restart scans from the beginning after each traversal, including previously cleaned late writes.
        continuation = page.nextContinuationToken();
    }
}
