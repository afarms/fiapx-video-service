package br.com.fiap.fiapx.video.infrastructure.persistence.repository;

import br.com.fiap.fiapx.video.infrastructure.persistence.entity.VideoEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface SpringVideoRepository extends JpaRepository<VideoEntity, UUID> {
    Optional<VideoEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    Page<VideoEntity> findByOwnerId(UUID ownerId, Pageable pageable);

    Optional<VideoEntity> findByOwnerIdAndIdempotencyKey(UUID ownerId, UUID idempotencyKey);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant databaseTime();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE videos SET upload_attempt_id=:next, original_object_key=:key, upload_lease_until=:lease
            WHERE id=:id AND upload_attempt_id=:previous AND status='UPLOADING'
                AND upload_lease_until <= clock_timestamp()
            """, nativeQuery = true)
    int takeOver(@Param("id") UUID id, @Param("previous") UUID previous, @Param("next") UUID next,
                 @Param("key") String key, @Param("lease") Instant lease);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE videos SET status='QUEUED', accepted_at=clock_timestamp()
            WHERE id=:id AND upload_attempt_id=:attempt AND status='UPLOADING'
                AND upload_lease_until > clock_timestamp()
            """, nativeQuery = true)
    int acceptAttempt(@Param("id") UUID id, @Param("attempt") UUID attempt);

    @Modifying
    @Query(value = """
            INSERT INTO video_upload_attempts(attempt_id, video_id, object_key, created_at, cleanup_after)
            VALUES (:attempt,:video,:key,clock_timestamp(),:cleanup)
            """, nativeQuery = true)
    int recordAttempt(@Param("attempt") UUID attempt, @Param("video") UUID video,
                      @Param("key") String key, @Param("cleanup") Instant cleanup);

    @Modifying
    @Query(value = """
            INSERT INTO video_outbox(event_id,video_id,owner_id,event_type,schema_version,payload,occurred_at,available_at)
            VALUES (:event,:video,:owner,'VideoProcessingRequested',1,CAST(:payload AS jsonb),:occurred,:occurred)
            """, nativeQuery = true)
    int enqueue(@Param("event") UUID event, @Param("video") UUID video, @Param("owner") UUID owner,
                @Param("payload") String payload, @Param("occurred") Instant occurred);

    @Query(value = "SELECT * FROM videos WHERE original_object_key=:key FOR UPDATE", nativeQuery = true)
    Optional<VideoEntity> lockByOriginalKey(@Param("key") String key);

    @Modifying
    @Query(value = "UPDATE video_upload_attempts SET cleaned_at=clock_timestamp() WHERE object_key=:key", nativeQuery = true)
    int cleaned(@Param("key") String key);
}
