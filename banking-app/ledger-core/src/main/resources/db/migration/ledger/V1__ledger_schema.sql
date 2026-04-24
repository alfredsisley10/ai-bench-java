CREATE SCHEMA IF NOT EXISTS ledger;

CREATE TABLE ledger.gl_account (
    code          VARCHAR(20) PRIMARY KEY,
    type          VARCHAR(16) NOT NULL,
    currency      VARCHAR(3)  NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    closed        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP   NOT NULL,
    created_by    VARCHAR(64) NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    updated_by    VARCHAR(64) NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE ledger.journal_entry (
    sequence       BIGSERIAL PRIMARY KEY,
    proposal_id    UUID         NOT NULL UNIQUE,
    posting_date   DATE         NOT NULL,
    posted_at      TIMESTAMP    NOT NULL,
    business_key   VARCHAR(128) NOT NULL UNIQUE,
    description    VARCHAR(512) NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    created_by     VARCHAR(64)  NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    updated_by     VARCHAR(64)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_journal_posting_date ON ledger.journal_entry (posting_date);

CREATE TABLE ledger.posting_line (
    id                BIGSERIAL PRIMARY KEY,
    journal_sequence  BIGINT       NOT NULL REFERENCES ledger.journal_entry (sequence),
    gl_account        VARCHAR(20)  NOT NULL REFERENCES ledger.gl_account (code),
    direction         VARCHAR(6)   NOT NULL,
    amount            NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency          VARCHAR(3)   NOT NULL,
    memo              VARCHAR(256)
);
CREATE INDEX idx_posting_line_account ON ledger.posting_line (gl_account);
CREATE INDEX idx_posting_line_journal ON ledger.posting_line (journal_sequence);
