CREATE TABLE processed_events (
    consumer_name VARCHAR(128) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload TEXT NOT NULL,
    correlation_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_outbox_events_published_created ON outbox_events (published_at, created_at);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id);

CREATE TABLE event_failures (
    id UUID PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    partition_number INT,
    offset_number BIGINT,
    consumer_name VARCHAR(128) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    last_error TEXT,
    status VARCHAR(32) NOT NULL,
    payload TEXT,
    first_failed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_failed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_event_failures_status ON event_failures (status);
CREATE INDEX idx_event_failures_event_consumer ON event_failures (event_id, consumer_name);
