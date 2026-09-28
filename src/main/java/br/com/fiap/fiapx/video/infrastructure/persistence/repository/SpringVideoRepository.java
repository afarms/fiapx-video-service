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
    @Query(value="SELECT * FROM videos WHERE id=:id FOR UPDATE",nativeQuery=true)
    Optional<VideoEntity> lockProcessing(UUID id);

    @Query(value="SELECT payload->>'correlationId' FROM video_outbox WHERE video_id=:id AND event_type='VideoProcessingRequested'",nativeQuery=true)
    String processingCorrelation(UUID id);

    @Query(value="SELECT canonical_event FROM video_processing_inbox WHERE event_id=:event",nativeQuery=true)
    String processingInbox(UUID event);

    @Modifying
    @Query(value="""
        INSERT INTO video_processing_inbox(event_id,video_id,event_version,canonical_event,outcome)
        VALUES (:event,:video,:version,:canonical,:outcome) ON CONFLICT (event_id) DO NOTHING
        """,nativeQuery=true)
    int insertProcessingInbox(UUID event,UUID video,long version,String canonical,String outcome);

    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query(value="""
        UPDATE videos SET status=:status,processing_version=:version,processing_attempt_id=:attemptId,processing_attempt=:attempt,
            completed_at=:completed,expires_at=:expires,failed_at=:failed,failure_code=:failure,
            result_bucket=:bucket,result_object_key=:key,result_size_bytes=:size,result_sha256=:hash,result_frame_count=:frames
        WHERE id=:id
        """,nativeQuery=true)
    int applyProcessing(UUID id,String status,long version,UUID attemptId,int attempt,Instant completed,Instant expires,
            Instant failed,String failure,String bucket,String key,Long size,String hash,Integer frames);

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
