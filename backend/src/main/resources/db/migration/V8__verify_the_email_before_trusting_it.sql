-- An account has to prove it owns its address before that address means anything.
--
-- The hole this closes is specific, not hypothetical. An invite is addressed to an email,
-- and accepting one checks that the caller's email matches it -- but nothing has ever
-- checked that the caller owns that email. So anyone could register victim@example.com,
-- accept an invitation meant for the real owner, and read that group's members, expenses
-- and balances. Registration has been open on the deployed demo since S6 with this gap in
-- place (SL-901).

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

-- Existing accounts are grandfathered, and it is worth being exact about what that means:
-- these rows have NOT proven anything. They predate the check, they are a handful of known
-- accounts on a demo deployment, and the alternative is locking the owner out of their own
-- groups on the morning this ships -- with no way back in, because delivering a
-- verification email is SL-902 and does not exist yet.
--
-- Written as a one-off backfill rather than a DEFAULT: a default would silently verify
-- every account created from now on, which is the entire thing being prevented.
UPDATE users SET email_verified_at = created_at WHERE email_verified_at IS NULL;

CREATE TABLE email_verification_tokens (
  id         UUID PRIMARY KEY,
  user_id    UUID NOT NULL REFERENCES users(id),
  -- SHA-256 hex of a 256-bit random token, like every other token in this schema. The raw
  -- value is handed out once and never stored, so a database leak yields nothing usable.
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  -- Single use. Stamped rather than deleted, so "this link was already used" and "this
  -- link never existed" remain distinguishable to an operator reading the table, while
  -- the API answers both the same way to whoever is holding the link.
  used_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The resend path asks "how many has this account been issued recently", and the verify
-- path looks up by hash, which the unique constraint already indexes.
CREATE INDEX idx_verification_user_recent
  ON email_verification_tokens(user_id, created_at DESC);
