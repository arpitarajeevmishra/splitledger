-- Who voided an expense, and when.
--
-- The activity feed must name who did what for every balance change, and a void is a
-- balance change. Without these columns the feed can only fall back on created_by and
-- created_at -- so a void would appear in the timeline at the moment the expense was
-- *created*, attributed to whoever created it rather than whoever voided it. Both are
-- wrong, and wrong in a way that reads as plausible, which is worse.
--
-- Nullable because an ACTIVE expense has not been voided. Rows that predate this
-- migration keep NULL: the feed treats a VOIDED expense with no voided_at as having been
-- voided at an unknown time by an unknown person, rather than inventing one.
ALTER TABLE expenses ADD COLUMN voided_at TIMESTAMPTZ;
ALTER TABLE expenses ADD COLUMN voided_by UUID REFERENCES users(id);

CREATE INDEX idx_expenses_group_voided ON expenses(group_id, voided_at DESC)
  WHERE voided_at IS NOT NULL;
