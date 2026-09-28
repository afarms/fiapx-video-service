package br.com.fiap.fiapx.video.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity
@Table(name = "video_outbox")
public class OutboxEntity {
    @Id @Column(name = "event_id") private UUID eventId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload", columnDefinition = "jsonb") private String payload;
    @Column(name = "attempts") private long attempts;
    protected OutboxEntity() { }
    public OutboxEntity(UUID eventId, String payload, long attempts) {
        this.eventId = eventId; this.payload = payload; this.attempts = attempts;
    }
    public UUID getEventId() { return eventId; }
    public String getPayload() { return payload; }
    public long getAttempts() { return attempts; }
}
