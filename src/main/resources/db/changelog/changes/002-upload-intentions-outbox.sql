--liquibase formatted sql

--changeset fiapx:002-upload-intentions-outbox
ALTER TABLE videos DROP CONSTRAINT ck_videos_initial_status;
ALTER TABLE videos ADD CONSTRAINT ck_videos_status CHECK (status IN ('UPLOADING', 'QUEUED'));
ALTER TABLE videos
    ADD COLUMN idempotency_key UUID,
    ADD COLUMN content_sha256 VARCHAR(64),
    ADD COLUMN upload_attempt_id UUID,
    ADD COLUMN upload_lease_until TIMESTAMPTZ,
    ADD COLUMN accepted_at TIMESTAMPTZ;

-- Legacy metadata has no client intention. Do not fabricate keys or fingerprints.
ALTER TABLE videos ADD CONSTRAINT ck_videos_upload_intention CHECK (
    (idempotency_key IS NULL AND content_sha256 IS NULL AND upload_attempt_id IS NULL
        AND upload_lease_until IS NULL AND accepted_at IS NULL AND status = 'UPLOADING')
    OR
    (idempotency_key IS NOT NULL AND content_sha256 IS NOT NULL
        AND content_sha256 ~ '^[0-9a-f]{64}$' AND upload_attempt_id IS NOT NULL
        AND upload_lease_until IS NOT NULL AND upload_lease_until >= created_at
        AND original_object_key = 'originals/' || owner_id::text || '/' || id::text || '/' || upload_attempt_id::text
        AND ((status = 'UPLOADING' AND accepted_at IS NULL)
            OR (status = 'QUEUED' AND accepted_at IS NOT NULL AND accepted_at >= created_at)))
);
CREATE UNIQUE INDEX ux_videos_owner_idempotency ON videos (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_videos_expired_upload ON videos (upload_lease_until, id)
    WHERE status = 'UPLOADING' AND idempotency_key IS NOT NULL;

-- Keep historical attempts for recoverable cleanup; accepted objects must always be rechecked.
CREATE TABLE video_upload_attempts (
    attempt_id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    object_key VARCHAR(1024) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    cleanup_after TIMESTAMPTZ NOT NULL,
    cleaned_at TIMESTAMPTZ,
    CONSTRAINT ck_upload_cleanup_time CHECK (cleanup_after >= created_at),
    CONSTRAINT ck_upload_object_key CHECK (length(trim(object_key)) > 0)
);
CREATE INDEX ix_upload_attempts_video ON video_upload_attempts (video_id);
CREATE INDEX ix_upload_attempts_cleanup ON video_upload_attempts (cleanup_after, attempt_id)
    WHERE cleaned_at IS NULL;

CREATE TABLE video_outbox (
    event_id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    owner_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    attempts BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_outbox_video_event UNIQUE (video_id, event_type),
    CONSTRAINT ck_outbox_event_type CHECK (event_type = 'VideoProcessingRequested'),
    CONSTRAINT ck_outbox_schema CHECK (schema_version = 1),
    CONSTRAINT ck_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_lease CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX ix_outbox_pending ON video_outbox (available_at, event_id) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_owner ON video_outbox (owner_id);

-- Roll forward only: dropping these structures would erase accepted intentions and pending work.
