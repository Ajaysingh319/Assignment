CREATE TABLE payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    provider_reference VARCHAR(64) UNIQUE,
    status VARCHAR(32) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    last_callback_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    succeeded_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
CREATE INDEX idx_payments_status ON payments (status);

CREATE TABLE payment_callbacks (
    id UUID PRIMARY KEY,
    provider_callback_id VARCHAR(64) UNIQUE NOT NULL,
    payment_id UUID NOT NULL,
    received_payload TEXT,
    result VARCHAR(32) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_callbacks_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);

CREATE INDEX idx_callbacks_payment_id ON payment_callbacks (payment_id);

CREATE TABLE reconciliation_records (
    id UUID PRIMARY KEY,
    mismatch_key VARCHAR(128) UNIQUE NOT NULL,
    order_id UUID,
    payment_id UUID,
    mismatch_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    details TEXT,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_reconciliation_status ON reconciliation_records (status);
CREATE INDEX idx_reconciliation_order_id ON reconciliation_records (order_id);
