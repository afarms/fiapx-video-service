package br.com.fiap.fiapx.video.infrastructure.persistence.repository;

import br.com.fiap.fiapx.video.infrastructure.persistence.entity.OutboxEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface SpringOutboxRepository extends JpaRepository<OutboxEntity, UUID> {
    @Query(value = """
            SELECT * FROM video_outbox WHERE published_at IS NULL AND available_at <= clock_timestamp()
                AND (lease_until IS NULL OR lease_until <= clock_timestamp())
            ORDER BY available_at, event_id LIMIT :batch FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntity> pending(@Param("batch") int batch);

    @Modifying
    @Query(value = """
            UPDATE video_outbox SET lease_token=:token, lease_until=clock_timestamp()+interval '60 seconds',
                attempts=attempts+1 WHERE event_id=:id
            """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("token") UUID token);

    @Modifying
    @Query(value = """
            UPDATE video_outbox SET published_at=clock_timestamp(), lease_token=NULL, lease_until=NULL
            WHERE event_id=:id AND lease_token=:token AND lease_until > clock_timestamp()
            """, nativeQuery = true)
    int published(@Param("id") UUID id, @Param("token") UUID token);

    @Modifying
    @Query(value = """
            UPDATE video_outbox SET available_at=clock_timestamp()+(:delay * interval '1 second'),
                lease_token=NULL, lease_until=NULL
            WHERE event_id=:id AND lease_token=:token AND lease_until > clock_timestamp()
            """, nativeQuery = true)
    int retry(@Param("id") UUID id, @Param("token") UUID token, @Param("delay") long delay);
}
