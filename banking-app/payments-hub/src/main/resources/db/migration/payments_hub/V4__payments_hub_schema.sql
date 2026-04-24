CREATE SCHEMA IF NOT EXISTS payments_hub;

CREATE TABLE payments_hub.payment (
    id                   UUID PRIMARY KEY,
    idempotency_key      VARCHAR(128) NOT NULL UNIQUE,
    rail                 VARCHAR(10)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    originator_account   VARCHAR(20)  NOT NULL,
    beneficiary_routing  VARCHAR(9),
    beneficiary_account  VARCHAR(34)  NOT NULL,
    beneficiary_name     VARCHAR(128) NOT NULL,
    amount               NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency             VARCHAR(3)   NOT NULL,
    requested_at         TIMESTAMP    NOT NULL,
    submitted_at         TIMESTAMP,
    settled_at           TIMESTAMP,
    memo                 VARCHAR(256),
    failure_reason       VARCHAR(512),
    created_at           TIMESTAMP    NOT NULL,
    created_by           VARCHAR(64)  NOT NULL,
    updated_at           TIMESTAMP    NOT NULL,
    updated_by           VARCHAR(64)  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_payment_status     ON payments_hub.payment (status);
CREATE INDEX idx_payment_originator ON payments_hub.payment (originator_account);
