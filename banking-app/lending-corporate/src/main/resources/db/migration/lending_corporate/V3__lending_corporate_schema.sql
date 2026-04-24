CREATE SCHEMA IF NOT EXISTS lending_corporate;

CREATE TABLE lending_corporate.commercial_loan (
    id                     UUID PRIMARY KEY,
    borrower               UUID           NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    structure              VARCHAR(24)    NOT NULL,
    principal_amount       NUMERIC(19,4)  NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    rate_bps               BIGINT         NOT NULL,
    day_count              VARCHAR(16)    NOT NULL,
    tenor_spec             VARCHAR(12)    NOT NULL,
    origination_date       DATE           NOT NULL,
    payment_frequency      VARCHAR(12)    NOT NULL,
    outstanding_principal  NUMERIC(19,4)  NOT NULL DEFAULT 0,
    total_drawn            NUMERIC(19,4)  NOT NULL DEFAULT 0,
    total_repaid           NUMERIC(19,4)  NOT NULL DEFAULT 0,
    created_at             TIMESTAMP      NOT NULL,
    created_by             VARCHAR(64)    NOT NULL,
    updated_at             TIMESTAMP      NOT NULL,
    updated_by             VARCHAR(64)    NOT NULL,
    version                BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_loan_borrower ON lending_corporate.commercial_loan (borrower);
CREATE INDEX idx_loan_status   ON lending_corporate.commercial_loan (status);

CREATE TABLE lending_corporate.loan_draw (
    id           UUID PRIMARY KEY,
    loan_id      UUID           NOT NULL REFERENCES lending_corporate.commercial_loan (id),
    draw_date    DATE           NOT NULL,
    amount       NUMERIC(19,4)  NOT NULL CHECK (amount > 0),
    currency     VARCHAR(3)     NOT NULL,
    purpose      VARCHAR(256),
    created_at   TIMESTAMP      NOT NULL,
    created_by   VARCHAR(64)    NOT NULL,
    updated_at   TIMESTAMP      NOT NULL,
    updated_by   VARCHAR(64)    NOT NULL,
    version      BIGINT         NOT NULL DEFAULT 0
);
