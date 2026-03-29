CREATE TABLE transfer_outbox
(
    id           BIGSERIAL    PRIMARY KEY,
    transfer_id  VARCHAR(36)  NOT NULL,
    aggregate_id VARCHAR(36)  NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    published    BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_unpublished ON transfer_outbox (published) WHERE published = false;
