CREATE TABLE participants (
    participant_id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL
);

CREATE TABLE liquidity_positions (
    participant_id VARCHAR(64) PRIMARY KEY REFERENCES participants(participant_id),
    available_minor BIGINT NOT NULL CHECK (available_minor >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payments (
    payment_id VARCHAR(64) PRIMARY KEY,
    source_participant_id VARCHAR(64) NOT NULL REFERENCES participants(participant_id),
    destination_participant_id VARCHAR(64) NOT NULL REFERENCES participants(participant_id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    priority VARCHAR(16) NOT NULL CHECK (priority IN ('URGENT','HIGH','NORMAL')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RECEIVED','QUEUED','SETTLED','REJECTED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    deadline TIMESTAMPTZ NULL,
    queued_at TIMESTAMPTZ NULL,
    settled_at TIMESTAMPTZ NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (source_participant_id <> destination_participant_id)
);
CREATE INDEX payments_queue_idx ON payments(status, priority, deadline, queued_at, payment_id);

CREATE TABLE settlements (
    settlement_id UUID PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL UNIQUE REFERENCES payments(payment_id),
    source_participant_id VARCHAR(64) NOT NULL REFERENCES participants(participant_id),
    destination_participant_id VARCHAR(64) NOT NULL REFERENCES participants(participant_id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    settlement_type VARCHAR(32) NOT NULL CHECK (settlement_type IN ('IMMEDIATE','QUEUED','GRIDLOCK_BATCH')),
    batch_id UUID NULL,
    settled_at TIMESTAMPTZ NOT NULL,
    CHECK ((settlement_type = 'GRIDLOCK_BATCH') = (batch_id IS NOT NULL))
);

CREATE TABLE processing_failures (
    failure_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id VARCHAR(64) NULL,
    failure_stage VARCHAR(64) NOT NULL,
    failure_message TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
