-- V8 — objections, and re-drawing without editing anything.
--
-- A published draw will be challenged, and sometimes the challenge will be right. An applicant's
-- category certificate was refused in error; a duplicate link joined two different people; an
-- application never reached the register at all.
--
-- The obvious remedy is to correct the record and reissue the list. That is exactly what this
-- system must never do. A published allotment that can be quietly amended is a published allotment
-- that proves nothing, and every hash, root and commitment published up to this point would be
-- worth precisely as much as the authority's word that it had not been amended.
--
-- So the remedy is a *new draw* that supersedes the old one. The original keeps its seed, its
-- registry root, its allotment and its audit trail, and remains verifiable forever. Anybody can
-- compare the two and see exactly what changed and why.

CREATE TABLE objection (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id            UUID        NOT NULL REFERENCES scheme(id),
    -- The draw being objected to. Null for an objection raised before any draw was held.
    draw_id              UUID        REFERENCES draw(id),
    -- The applicant it concerns. Null for an objection about the conduct of the draw itself,
    -- which anybody may raise — a newspaper, for instance.
    application_no       TEXT,

    ground               TEXT        NOT NULL,
    statement            TEXT        NOT NULL,
    supporting_reference TEXT,
    filed_by             TEXT        NOT NULL,
    filed_at             TIMESTAMPTZ NOT NULL,

    status               TEXT        NOT NULL,
    decided_at           TIMESTAMPTZ,
    decided_by           TEXT,
    -- Why. An objection dismissed without a stated reason is an objection that was not answered.
    decision_reason      TEXT,
    -- What upholding it requires somebody to do. Recorded because upholding an objection changes
    -- nothing on its own; the correction and the re-draw are separate, deliberate acts.
    remedy               TEXT,

    CONSTRAINT objection_ground_known CHECK (ground IN (
        'ELIGIBILITY_WRONGLY_ASSESSED',
        'CATEGORY_CLAIM_WRONGLY_REFUSED',
        'WRONGLY_TREATED_AS_DUPLICATE',
        'APPLICATION_MISSING_FROM_REGISTER',
        'DRAW_IMPROPERLY_CONDUCTED',
        'OTHER')),
    CONSTRAINT objection_status_known
        CHECK (status IN ('OPEN', 'UPHELD', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT objection_decided_has_reason
        CHECK (status = 'OPEN' OR (decided_at IS NOT NULL AND decided_by IS NOT NULL AND decision_reason IS NOT NULL))
);

CREATE INDEX objection_scheme_idx ON objection (scheme_id, status, filed_at);
CREATE INDEX objection_draw_idx   ON objection (draw_id, status);

COMMENT ON TABLE objection IS 'Challenges to a published result. Upholding one never edits the draw; it authorises a superseding draw.';

-- ---------------------------------------------------------------------------
-- Supersession.
--
-- One direction only, and that is forced rather than chosen: a published draw cannot be updated at
-- all — the trigger from V6 refuses every change — so it cannot be marked as superseded. The new
-- draw points back at the old one instead, and "was this draw superseded?" is answered by looking
-- for its successor.
--
-- The constraint is a nice accident of the immutability rule: nothing about the original changes
-- when it is replaced, including its status. It stays PUBLISHED, verifiable, and exactly what it
-- always was.
-- ---------------------------------------------------------------------------
ALTER TABLE draw ADD COLUMN supersedes_draw_id UUID REFERENCES draw(id);

-- A draw has at most one successor. Two draws both claiming to replace the same one would leave
-- nobody able to say which allotment stands.
CREATE UNIQUE INDEX draw_one_successor_idx
    ON draw (supersedes_draw_id)
    WHERE supersedes_draw_id IS NOT NULL;

ALTER TABLE draw ADD CONSTRAINT draw_does_not_supersede_itself
    CHECK (supersedes_draw_id IS NULL OR supersedes_draw_id <> id);

COMMENT ON COLUMN draw.supersedes_draw_id IS 'The published draw this one replaces. The replaced draw is never modified.';
