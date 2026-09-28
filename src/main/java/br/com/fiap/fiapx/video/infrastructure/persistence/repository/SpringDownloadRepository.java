package br.com.fiap.fiapx.video.infrastructure.persistence.repository;

import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface SpringDownloadRepository extends Repository<VideoEntity, UUID> {
    interface ResultRow {
        UUID getId(); UUID getOwnerId(); String getStatus(); Instant getExpiresAt();
        String getResultBucket(); String getResultObjectKey(); long getResultSizeBytes(); String getResultSha256();
        Instant getResultDeletedAt(); UUID getResultCleanupToken();
    }
    String COLUMNS = """
        SELECT id,owner_id AS "ownerId",status,expires_at AS "expiresAt",
            result_bucket AS "resultBucket",result_object_key AS "resultObjectKey",
            result_size_bytes AS "resultSizeBytes",result_sha256 AS "resultSha256",
            result_deleted_at AS "resultDeletedAt",result_cleanup_token AS "resultCleanupToken"
        FROM videos
        """;
    @Query(value = COLUMNS + " WHERE id=:id FOR UPDATE", nativeQuery = true)
    Optional<ResultRow> lock(UUID id);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant now();

    @Modifying
    @Query(value = """
        INSERT INTO video_download_leases(token,video_id,created_at,valid_until,deadline)
        VALUES (:token,:video,:now,:until,:deadline)
        """, nativeQuery = true)
    int insertLease(UUID token, UUID video, Instant now, Instant until, Instant deadline);

    @Modifying
    @Query(value = """
        UPDATE video_download_leases SET valid_until=LEAST(deadline,:until)
        WHERE token=:token AND video_id=:video AND valid_until>clock_timestamp() AND deadline>clock_timestamp()
        """, nativeQuery = true)
    int renew(UUID video, UUID token, Instant until);

    @Query(value = "SELECT valid_until FROM video_download_leases WHERE token=:token AND video_id=:video", nativeQuery = true)
    Instant leaseUntil(UUID video, UUID token);

    @Modifying
    @Query(value = "DELETE FROM video_download_leases WHERE video_id=:video AND token=:token", nativeQuery = true)
    int release(UUID video, UUID token);

    @Query(value = COLUMNS + """
        WHERE status='COMPLETED' AND expires_at<=clock_timestamp() AND result_deleted_at IS NULL
            AND result_cleanup_available_at<=clock_timestamp()
            AND (result_cleanup_until IS NULL OR result_cleanup_until<=clock_timestamp())
            AND NOT EXISTS (SELECT 1 FROM video_download_leases d WHERE d.video_id=videos.id AND d.valid_until>clock_timestamp())
        ORDER BY expires_at,id LIMIT :limit FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<ResultRow> lockExpired(int limit);

    @Modifying
    @Query(value = "UPDATE videos SET result_cleanup_token=:token,result_cleanup_until=:until WHERE id=:video", nativeQuery = true)
    int claim(UUID video, UUID token, Instant until);

    @Modifying
    @Query(value = """
        UPDATE videos SET result_deleted_at=clock_timestamp(),result_cleanup_token=NULL,result_cleanup_until=NULL
        WHERE id=:video AND result_cleanup_token=:token AND result_cleanup_until>clock_timestamp()
        """, nativeQuery = true)
    int deleted(UUID video, UUID token);

    @Modifying
    @Query(value = """
        UPDATE videos SET result_cleanup_token=NULL,result_cleanup_until=NULL,result_cleanup_available_at=:available
        WHERE id=:video AND result_cleanup_token=:token AND result_cleanup_until>clock_timestamp()
        """, nativeQuery = true)
    int retry(UUID video, UUID token, Instant available);

    @Modifying
    @Query(value = "DELETE FROM video_download_leases WHERE video_id=:video AND valid_until<=clock_timestamp()", nativeQuery = true)
    int prune(UUID video);
}
