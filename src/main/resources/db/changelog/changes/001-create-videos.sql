--liquibase formatted sql

--changeset fiapx:001-create-videos
CREATE TABLE videos (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    original_object_key VARCHAR(1024) NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_videos_size CHECK (size_bytes BETWEEN 1 AND 100000000),
    CONSTRAINT ck_videos_initial_status CHECK (status = 'UPLOADING'),
    CONSTRAINT ck_videos_name CHECK (length(trim(original_name)) > 0),
    CONSTRAINT ck_videos_object_key CHECK (length(trim(original_object_key)) > 0)
);

CREATE INDEX ix_videos_owner_created ON videos (owner_id, created_at DESC, id);

-- Roll-forward is preferred after data exists. Destructive rollback requires a backup and explicit approval.
--rollback DROP TABLE videos;
