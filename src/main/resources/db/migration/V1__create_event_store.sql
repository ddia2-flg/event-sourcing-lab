CREATE TABLE account_events
(
    id           BIGSERIAL    PRIMARY KEY,
    aggregate_id VARCHAR(36)  NOT NULL,
    version      BIGINT       NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    metadata     JSONB,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_aggregate_version UNIQUE (aggregate_id, version)
);

CREATE INDEX idx_account_events_aggregate_id ON account_events (aggregate_id);
CREATE INDEX idx_account_events_occurred_at ON account_events (occurred_at);
