-- A transaction may be reversed at most once.
--
-- V1's uq_ledger_txn_source exempts reversals, which it must: a reversal shares its
-- source_id with the transaction it undoes, so including it would forbid reversing
-- anything. That left a hole -- nothing stopped a second reversal of the same
-- transaction, which would silently re-apply the original and double a balance.
--
-- The ledger service refuses it too. Both, because the service check is a read followed
-- by a write and two concurrent voids can pass it at the same time; only the index
-- actually decides.
CREATE UNIQUE INDEX uq_ledger_txn_reverses ON ledger_transactions(reverses_txn_id)
  WHERE reverses_txn_id IS NOT NULL;
