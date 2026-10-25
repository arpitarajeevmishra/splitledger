-- A group can be put away without being destroyed.
--
-- Archiving rather than deleting, because the ledger is immutable by design (ADR-003) and
-- a group's rows are the evidence behind every balance anyone ever saw. Deleting a group
-- with history would either orphan those postings or erase a record somebody settled money
-- against; neither is a thing this product should offer under a button labelled "delete".
--
-- Nullable, with NULL meaning active: the common query is "the groups I am in", and a
-- partial index on the null case keeps that read as cheap as it was before this column
-- existed.
ALTER TABLE expense_groups ADD COLUMN archived_at TIMESTAMPTZ;

CREATE INDEX idx_expense_groups_active ON expense_groups(id) WHERE archived_at IS NULL;
