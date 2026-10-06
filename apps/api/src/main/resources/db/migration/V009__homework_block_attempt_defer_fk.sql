-- Allow the "overwrite attempt" transaction to forward-reference a row that
-- does not exist yet at the point the reference is written.
--
-- Background: HomeworkBlockAttemptService.submitAttempt stamps the current
-- live row's replaced_by_id with a freshly-minted UUID *before* inserting
-- the new row that carries that UUID as its primary key. This ordering is
-- deliberate — it takes the old row out of the partial unique index
-- (homework_block_attempt_current_idx) BEFORE the new row enters, so the
-- "at most one live attempt" invariant is never violated mid-transaction.
--
-- The V008 FK was IMMEDIATE, which caused the UPDATE on prev to fail at
-- flush() time with "Key (replaced_by_id)=(...) is not present" — the new
-- row hadn't been inserted yet. Deferring the check to commit time resolves
-- this cleanly: by the time the transaction commits, the INSERT has
-- populated the target row, and the FK validates.
--
-- INITIALLY DEFERRED means the check is deferred by default without the
-- service needing to issue SET CONSTRAINTS. Semantically equivalent to
-- IMMEDIATE at transaction boundary — only the mid-transaction timing
-- changes.

ALTER TABLE homework_block_attempt
    DROP CONSTRAINT homework_block_attempt_replaced_by_id_fkey;

ALTER TABLE homework_block_attempt
    ADD CONSTRAINT homework_block_attempt_replaced_by_id_fkey
    FOREIGN KEY (replaced_by_id)
    REFERENCES homework_block_attempt(id)
    ON DELETE SET NULL
    DEFERRABLE INITIALLY DEFERRED;
