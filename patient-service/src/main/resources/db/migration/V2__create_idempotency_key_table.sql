-- Idempotency keys for POST /patients (replay-safe retries).
CREATE TABLE idempotency_key
(
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_hash    VARCHAR(64)              NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    response_status INTEGER,
    response_body   VARCHAR(4000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

-- Supports a future purge of old keys (DELETE ... WHERE created_at < now() - interval '24 hours').
CREATE INDEX ix_idempotency_key_created_at ON idempotency_key (created_at);