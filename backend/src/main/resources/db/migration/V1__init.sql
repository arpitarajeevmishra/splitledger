-- SplitLedger initial schema.
-- Mirrors LLD section 3.2 including the corrections from REVIEW_FINDINGS.md
-- (C2, C4, C10, C18, C21, C22 and the group_invites table from M1).
-- Forward-only and backward-compatible: later migrations add, backfill, then remove.

CREATE TABLE users (
  id            UUID PRIMARY KEY,
  email         VARCHAR(254) NOT NULL UNIQUE,
  name          VARCHAR(100) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  upi_id        VARCHAR(100),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
  id          UUID PRIMARY KEY,
  user_id     UUID NOT NULL REFERENCES users(id),
  token_hash  VARCHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex is exactly 64 chars
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked_at  TIMESTAMPTZ,
  replaced_by UUID
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

CREATE TABLE expense_groups (
  id         UUID PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,
  currency   CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),   -- v1 is INR-only (C21)
  created_by UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Re-joining reuses the row: the PK forbids a second one, so adding a departed
-- member is an UPSERT that clears left_at. Ledger history survives departure (C22).
CREATE TABLE group_members (
  group_id  UUID NOT NULL REFERENCES expense_groups(id),
  user_id   UUID NOT NULL REFERENCES users(id),
  role      VARCHAR(10) NOT NULL CHECK (role IN ('ADMIN','MEMBER')),
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  left_at   TIMESTAMPTZ,
  PRIMARY KEY (group_id, user_id)
);

CREATE TABLE expenses (
  id                  UUID PRIMARY KEY,
  group_id            UUID NOT NULL REFERENCES expense_groups(id),
  paid_by             UUID NOT NULL REFERENCES users(id),
  description         VARCHAR(200) NOT NULL,
  -- Upper bound is not cosmetic: allocate() computes total * weight, and the cap
  -- is what keeps that product inside a long (C2, LLD 4.1).
  amount_paise        BIGINT NOT NULL CHECK (amount_paise BETWEEN 1 AND 100000000),
  currency            CHAR(3) NOT NULL DEFAULT 'INR',
  split_type          VARCHAR(10) NOT NULL CHECK (split_type IN ('EQUAL','EXACT','PERCENT','SHARES')),
  status              VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','VOIDED')),
  replaces_expense_id UUID REFERENCES expenses(id),
  expense_date        DATE NOT NULL,
  created_by          UUID NOT NULL REFERENCES users(id),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  version             BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_expenses_group ON expenses(group_id, expense_date DESC);

CREATE TABLE expense_splits (
  expense_id  UUID NOT NULL REFERENCES expenses(id),
  user_id     UUID NOT NULL REFERENCES users(id),
  owed_paise  BIGINT NOT NULL CHECK (owed_paise >= 0),
  input_value BIGINT,          -- exact paise / basis points / share count as entered
  PRIMARY KEY (expense_id, user_id)
);

CREATE TABLE ledger_transactions (
  id              UUID PRIMARY KEY,
  group_id        UUID NOT NULL REFERENCES expense_groups(id),
  type            VARCHAR(12) NOT NULL CHECK (type IN ('EXPENSE','SETTLEMENT','REVERSAL')),
  source_id       UUID NOT NULL,
  reverses_txn_id UUID REFERENCES ledger_transactions(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One ledger transaction per business fact. A retry or a bug cannot post the same
-- expense or settlement twice; reversals are exempt (C4).
CREATE UNIQUE INDEX uq_ledger_txn_source ON ledger_transactions(type, source_id)
  WHERE type <> 'REVERSAL';

CREATE TABLE ledger_postings (
  id           BIGSERIAL PRIMARY KEY,
  txn_id       UUID NOT NULL REFERENCES ledger_transactions(id),
  group_id     UUID NOT NULL,
  user_id      UUID NOT NULL REFERENCES users(id),
  -- Positive: the group owes this user. Negative: this user owes the group.
  -- Every transaction's postings sum to exactly zero.
  amount_paise BIGINT NOT NULL CHECK (amount_paise <> 0)
);
CREATE INDEX idx_postings_group_user ON ledger_postings(group_id, user_id);
CREATE INDEX idx_postings_txn ON ledger_postings(txn_id);          -- reversal lookups (C18)

CREATE TABLE settlements (
  id                 UUID PRIMARY KEY,
  group_id           UUID NOT NULL REFERENCES expense_groups(id),
  from_user          UUID NOT NULL REFERENCES users(id),
  to_user            UUID NOT NULL REFERENCES users(id),
  amount_paise       BIGINT NOT NULL CHECK (amount_paise > 0),
  method             VARCHAR(12) NOT NULL CHECK (method IN ('RAZORPAY','UPI_INTENT','CASH','SANDBOX')),
  status             VARCHAR(12) NOT NULL CHECK (status IN ('INITIATED','PENDING','CONFIRMED','FAILED','CANCELLED','EXPIRED')),
  gateway_order_id   VARCHAR(64) UNIQUE,
  gateway_payment_id VARCHAR(64),
  expires_at         TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  confirmed_at       TIMESTAMPTZ,
  version            BIGINT NOT NULL DEFAULT 0,
  CHECK (from_user <> to_user)
);
CREATE INDEX idx_settlements_group ON settlements(group_id, created_at DESC);
CREATE INDEX idx_settlements_pending ON settlements(status, expires_at) WHERE status = 'PENDING';

CREATE TABLE webhook_events (
  id                UUID PRIMARY KEY,
  provider          VARCHAR(20) NOT NULL,
  provider_event_id VARCHAR(100) NOT NULL,
  event_type        VARCHAR(50) NOT NULL,
  payload           JSONB NOT NULL,
  received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at      TIMESTAMPTZ,
  status            VARCHAR(12) NOT NULL DEFAULT 'RECEIVED'
                    CHECK (status IN ('RECEIVED','PROCESSED','FAILED','IGNORED')),   -- C18
  UNIQUE (provider, provider_event_id)
);

CREATE TABLE idempotency_keys (
  user_id         UUID NOT NULL REFERENCES users(id),
  idem_key        VARCHAR(100) NOT NULL,
  request_hash    VARCHAR(64) NOT NULL,
  status          VARCHAR(12) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_status INT,
  response_body   JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, idem_key)
);

CREATE TABLE outbox_events (
  id             UUID PRIMARY KEY,
  aggregate_type VARCHAR(30) NOT NULL,
  aggregate_id   UUID NOT NULL,
  event_type     VARCHAR(50) NOT NULL,
  payload        JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (        -- consumer-side de-duplication
  consumer   VARCHAR(50) NOT NULL,
  event_id   UUID NOT NULL,
  handled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE notifications (
  id         UUID PRIMARY KEY,
  user_id    UUID NOT NULL REFERENCES users(id),
  type       VARCHAR(40) NOT NULL,
  payload    JSONB NOT NULL,
  read_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications(user_id, created_at DESC);   -- C18

-- Nobody joins a group without accepting. An invite may target an email that has
-- no account yet; accepting after registration is the same code path (M1, FR-10).
CREATE TABLE group_invites (
  id           UUID PRIMARY KEY,
  group_id     UUID NOT NULL REFERENCES expense_groups(id),
  email        VARCHAR(254) NOT NULL,
  invited_by   UUID NOT NULL REFERENCES users(id),
  token_hash   VARCHAR(64) NOT NULL UNIQUE,          -- SHA-256 of the emailed token
  status       VARCHAR(10) NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','REVOKED')),
  expires_at   TIMESTAMPTZ NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  responded_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_invite_open ON group_invites(group_id, lower(email))
  WHERE status = 'PENDING';
