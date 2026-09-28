--liquibase formatted sql

--changeset fiapx:004-processing-reconciliation
ALTER TABLE video_outbox
    ADD COLUMN reconciled_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_count BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_outbox_reconciliation_count CHECK (reconciliation_count >= 0);
CREATE INDEX ix_outbox_result_overdue ON video_outbox (published_at, event_id)
    WHERE published_at IS NOT NULL;
