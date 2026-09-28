--liquibase formatted sql
--changeset fiapx:005-download-retention
ALTER TABLE videos
    ADD COLUMN result_deleted_at TIMESTAMPTZ,
    ADD COLUMN result_cleanup_token UUID,
    ADD COLUMN result_cleanup_until TIMESTAMPTZ,
    ADD COLUMN result_cleanup_available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE videos ADD CONSTRAINT ck_videos_result_cleanup CHECK (
    (result_cleanup_token IS NULL) = (result_cleanup_until IS NULL)
    AND (result_cleanup_token IS NULL OR (status='COMPLETED' AND result_deleted_at IS NULL))
    AND (result_deleted_at IS NULL OR (status='COMPLETED' AND result_deleted_at>=expires_at))
);
CREATE TABLE video_download_leases (
    token UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    created_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    CHECK (created_at<valid_until AND valid_until<=deadline)
);
CREATE INDEX ix_video_download_active ON video_download_leases(video_id,valid_until);
CREATE INDEX ix_video_result_cleanup ON videos(result_cleanup_available_at,expires_at,id)
    WHERE status='COMPLETED' AND result_deleted_at IS NULL;
-- Historical result references, status, inbox and outbox are preserved after physical deletion.
