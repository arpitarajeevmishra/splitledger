-- An event that can never be published has to stop being retried.
--
-- The publisher catches failures per event so that one bad event cannot wedge the batch
-- behind it (D-134). The cost of that choice, recorded as O-16 at the time, is that such an
-- event is re-claimed every poll forever: a row that no amount of retrying will fix, taking
-- a slot in every batch and a line in every log, indefinitely.
--
-- Retrying is still the right first answer. A broker that is down, a handler that failed on
-- a deadlock, a deploy mid-flight -- all of those succeed on the next attempt, and giving up
-- after one would turn a blip into lost work. So: retry, count, and stop.
--
-- attempts counts failures, not deliveries. An event published first time has attempts = 0,
-- which is why the column means "how many times this did not work" rather than "how many
-- times this was tried".
ALTER TABLE outbox_events ADD COLUMN attempts INT NOT NULL DEFAULT 0;

-- Stamped when the publisher gives up. Nullable and never set back: a dead-lettered event
-- stays in the table with its payload intact, because the question it has to answer later
-- is "what was this, and why did nobody get it", and a deleted row answers neither.
ALTER TABLE outbox_events ADD COLUMN dead_lettered_at TIMESTAMPTZ;

-- The publisher's query gained a second condition, so the index it uses has to as well --
-- otherwise every poll scans the dead letters it is about to discard, and the longer the
-- system runs the more of them there are.
DROP INDEX idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at)
  WHERE published_at IS NULL AND dead_lettered_at IS NULL;

-- And an index for the only question anyone asks about dead letters, which is whether there
-- are any. Partial, so it holds nothing at all on a healthy system.
CREATE INDEX idx_outbox_dead_lettered ON outbox_events(dead_lettered_at)
  WHERE dead_lettered_at IS NOT NULL;
