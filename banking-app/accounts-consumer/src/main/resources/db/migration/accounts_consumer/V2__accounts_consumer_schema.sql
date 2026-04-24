CREATE SCHEMA IF NOT EXISTS accounts_consumer;

CREATE TABLE accounts_consumer.consumer_account (
    account_number  VARCHAR(20) PRIMARY KEY,
    customer_id     UUID        NOT NULL,
    product         VARCHAR(32) NOT NULL,
    currency        VARCHAR(3)  NOT NULL,
    status          VARCHAR(16) NOT NULL,
    opened_on       DATE        NOT NULL,
    matures_on      DATE,
    closed_at       TIMESTAMP,
    freeze_reason   VARCHAR(256),
    created_at      TIMESTAMP   NOT NULL,
    created_by      VARCHAR(64) NOT NULL,
    updated_at      TIMESTAMP   NOT NULL,
    updated_by      VARCHAR(64) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_consumer_account_customer ON accounts_consumer.consumer_account (customer_id);

CREATE TABLE accounts_consumer.hold (
    id              UUID PRIMARY KEY,
    account_number  VARCHAR(20)   NOT NULL REFERENCES accounts_consumer.consumer_account (account_number),
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3)    NOT NULL,
    reason          VARCHAR(256),
    placed_at       TIMESTAMP     NOT NULL,
    expires_at      TIMESTAMP     NOT NULL,
    released_at     TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL,
    created_by      VARCHAR(64)   NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    updated_by      VARCHAR(64)   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_hold_account_active ON accounts_consumer.hold (account_number, released_at);
