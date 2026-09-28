--liquibase formatted sql
--changeset fiapx:003-processing-results
ALTER TABLE videos DROP CONSTRAINT ck_videos_status;
ALTER TABLE videos ADD CONSTRAINT ck_videos_status CHECK (status IN ('UPLOADING','QUEUED','PROCESSING','COMPLETED','FAILED'));
ALTER TABLE videos DROP CONSTRAINT ck_videos_upload_intention;
ALTER TABLE videos ADD CONSTRAINT ck_videos_upload_intention CHECK (
    (idempotency_key IS NULL AND content_sha256 IS NULL AND upload_attempt_id IS NULL
        AND upload_lease_until IS NULL AND accepted_at IS NULL AND status='UPLOADING')
    OR
    (idempotency_key IS NOT NULL AND content_sha256 IS NOT NULL
        AND content_sha256 ~ '^[0-9a-f]{64}$' AND upload_attempt_id IS NOT NULL
        AND upload_lease_until IS NOT NULL AND upload_lease_until>=created_at
        AND original_object_key='originals/' || owner_id::text || '/' || id::text || '/' || upload_attempt_id::text
        AND ((status='UPLOADING' AND accepted_at IS NULL)
            OR (status<>'UPLOADING' AND accepted_at IS NOT NULL AND accepted_at>=created_at)))
);
ALTER TABLE videos
    ADD COLUMN processing_version BIGINT NOT NULL DEFAULT 0 CHECK (processing_version>=0),
    ADD COLUMN processing_attempt_id UUID,
    ADD COLUMN processing_attempt INTEGER,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN failed_at TIMESTAMPTZ,
    ADD COLUMN failure_code VARCHAR(64),
    ADD COLUMN result_bucket VARCHAR(63),
    ADD COLUMN result_object_key VARCHAR(1024),
    ADD COLUMN result_size_bytes BIGINT,
    ADD COLUMN result_sha256 VARCHAR(64),
    ADD COLUMN result_frame_count INTEGER;
ALTER TABLE videos ADD CONSTRAINT ck_videos_processing_version CHECK (
    (status IN ('UPLOADING','QUEUED') AND processing_version=0 AND processing_attempt_id IS NULL AND processing_attempt IS NULL)
    OR (status IN ('PROCESSING','COMPLETED','FAILED') AND processing_version>0 AND processing_attempt_id IS NOT NULL
        AND processing_attempt IS NOT NULL AND processing_attempt>0)
);
ALTER TABLE videos ADD CONSTRAINT ck_videos_completion CHECK (
    (status='COMPLETED' AND completed_at IS NOT NULL AND expires_at IS NOT NULL
        AND expires_at=completed_at+interval '24 hours' AND result_bucket IS NOT NULL
        AND result_object_key IS NOT NULL AND result_size_bytes BETWEEN 1 AND 1073741824
        AND result_size_bytes IS NOT NULL AND result_sha256 IS NOT NULL AND result_sha256 ~ '^[0-9a-f]{64}$'
        AND result_frame_count IS NOT NULL AND result_frame_count>0)
    OR (status<>'COMPLETED' AND completed_at IS NULL AND expires_at IS NULL AND result_bucket IS NULL
        AND result_object_key IS NULL AND result_size_bytes IS NULL AND result_sha256 IS NULL AND result_frame_count IS NULL)
);
ALTER TABLE videos ADD CONSTRAINT ck_videos_failure CHECK (
    (status='FAILED' AND failed_at IS NOT NULL AND failure_code IS NOT NULL
        AND failure_code IN ('INVALID_MEDIA','DURATION_EXCEEDED','OUTPUT_LIMIT_EXCEEDED','PROCESSING_TIMEOUT','PROCESSING_FAILED'))
    OR (status<>'FAILED' AND failed_at IS NULL AND failure_code IS NULL)
);
CREATE TABLE video_processing_inbox (
    event_id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    event_version BIGINT NOT NULL CHECK (event_version>0),
    canonical_event TEXT NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPLIED','IGNORED')),
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(video_id,event_version)
);
-- Roll forward only; inbox and private references are durable recovery data.
