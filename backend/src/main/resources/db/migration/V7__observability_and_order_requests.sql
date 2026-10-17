-- Distinguishes events actually acknowledged by Kafka from deliberate test-only simulation.
ALTER TABLE outbox_events ADD COLUMN published_via VARCHAR(16);
CREATE INDEX idx_outbox_events_aggregate_id ON outbox_events (aggregate_id);

-- Every order-creation request is recorded with its outcome, including requests that were
-- rejected (insufficient stock, invalid input, idempotency conflict) and idempotent replays.
-- Rejected requests never become orders; this table is the operation history for them.
CREATE TABLE order_requests (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    order_id UUID,
    reason TEXT,
    item_count INT NOT NULL,
    total_quantity BIGINT NOT NULL,
    request_summary TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_order_requests_created_at ON order_requests (created_at);
CREATE INDEX idx_order_requests_idempotency_key ON order_requests (idempotency_key);
CREATE INDEX idx_order_requests_order_id ON order_requests (order_id);

ALTER TABLE reconciliation_records ADD COLUMN resolution_action VARCHAR(64);
ALTER TABLE reconciliation_records ADD COLUMN resolution_notes TEXT;

CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
