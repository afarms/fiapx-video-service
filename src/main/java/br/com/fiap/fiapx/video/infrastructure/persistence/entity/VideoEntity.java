package br.com.fiap.fiapx.video.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "videos")
public class VideoEntity implements Persistable<UUID> {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "original_object_key", nullable = false, unique = true, length = 1024, updatable = false)
    private String originalObjectKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = true;

    protected VideoEntity() {
        // Required by JPA; domain validation belongs to Video.
    }

    public VideoEntity(UUID id, UUID ownerId, String originalName, String originalObjectKey,
                       long sizeBytes, String status, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.originalName = originalName;
        this.originalObjectKey = originalObjectKey;
        this.sizeBytes = sizeBytes;
        this.status = status;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getOriginalName() { return originalName; }
    public String getOriginalObjectKey() { return originalObjectKey; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean isNew() { return newEntity; }

    @PostPersist
    @PostLoad
    void markNotNew() {
        newEntity = false;
    }
}
