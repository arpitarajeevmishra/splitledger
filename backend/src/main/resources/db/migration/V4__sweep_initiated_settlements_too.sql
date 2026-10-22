-- The expiry sweeper reclaims INITIATED as well as PENDING (finding C17).
--
-- V1's partial index covers only PENDING, which was right when INITIATED had no expiry
-- edge. It does now: a settlement abandoned before any order was created would otherwise
-- sit in the list forever, and the sweeper would have to seq-scan to find it.
DROP INDEX IF EXISTS idx_settlements_pending;

CREATE INDEX idx_settlements_unfinished ON settlements(status, expires_at)
  WHERE status IN ('INITIATED', 'PENDING');
