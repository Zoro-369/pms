-- Transactional outbox: events are written in the SAME transaction as the business change,
-- and then a relay reads the events and publishes them to the message broker.
CREATE TABLE outbox_event
(
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(50)              NOT NULL,
    aggregate_id   VARCHAR(64)              NOT NULL,
    event_type     VARCHAR(50)              NOT NULL,
    payload        BYTEA                    NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMP WITH TIME ZONE
);

-- Lets the relay find unpublished rows in order (published_at IS NULL, oldest first).
-- A partial index (WHERE published_at IS NULL) would be smaller on PostgreSQL, but the plain
-- index keeps the migration portable to the H2 database used by the context-load test.
CREATE INDEX ix_outbox_event_published_created ON outbox_event (published_at, created_at);