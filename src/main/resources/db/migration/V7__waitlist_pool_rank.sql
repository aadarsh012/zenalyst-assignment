-- V7 — record where a waiting applicant actually came.
--
-- draw_waitlist stored a queue position: first in line, second in line. That is what you need to
-- fill a surrendered flat, and it is not what an applicant is asking when they ask where they came.
--
-- The two are not the same number. A horizontal top-up displaces somebody from inside the merit
-- cutoff, so the person at the front of the queue may be ranked above somebody who was allotted a
-- flat. Answering "you were first on the waiting list" to someone who was in fact ranked 87th out
-- of 90 seats tells them nothing they can check, and answering it to someone ranked 4th would be
-- actively misleading.
--
-- V6 is applied and is therefore not edited (ADR-0001); the column is added here instead.
ALTER TABLE draw_waitlist ADD COLUMN pool_rank INTEGER;

COMMENT ON COLUMN draw_waitlist.position  IS 'Place in the queue for a surrendered flat: 1 is next.';
COMMENT ON COLUMN draw_waitlist.pool_rank IS 'Where this applicant actually came in the pool. Differs from position wherever a horizontal reservation displaced someone.';
