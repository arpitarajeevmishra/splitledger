-- The outbox row has to carry the group it belongs to.
--
-- ADR-005 publishes each event keyed by group, which is what gives per-group ordering: two
-- expenses recorded against the same group must reach a consumer in the order they were
-- written, while unrelated groups are free to interleave. Without a column to key on, the
-- publisher would have to either dig the group out of the JSON payload -- making the
-- ordering guarantee depend on every emitter spelling a field the same way -- or key by
-- aggregate_id, which for an expense event is the expense, so every event would land in a
-- partition of its own and the ordering claim would quietly be false.
--
-- Nullable because not every event a system like this grows will belong to a group: a
-- future account-level event has no group and should not be forced to invent one. The
-- publisher falls back to aggregate_id for those, which is the right key for an event whose
-- ordering is per-aggregate.
ALTER TABLE outbox_events ADD COLUMN group_id UUID;

-- The publisher's query is "unpublished, oldest first". It already has a partial index for
-- that; group_id is carried along for the key rather than filtered on, so it needs no index
-- of its own.
