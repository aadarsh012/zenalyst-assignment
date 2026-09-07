-- V1 — baseline schema.
--
-- Two things live here, and only two: the root aggregate every later phase hangs off
-- (scheme), and the append-only audit substrate that every later phase writes to.
--
-- The audit table is created in phase 0 on purpose. An audit log added at the end of a
-- project records only the end of the project; to be evidence, it has to exist before
-- the first decision is taken.

-- ---------------------------------------------------------------------------
-- scheme — one housing scheme: the flats, the application window, the status.
-- ---------------------------------------------------------------------------
CREATE TABLE scheme (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code                   TEXT        NOT NULL UNIQUE,
    name                   TEXT        NOT NULL,
    total_flats            INTEGER     NOT NULL,
    applications_open_at   TIMESTAMPTZ NOT NULL,
    applications_close_at  TIMESTAMPTZ NOT NULL,
    status                 TEXT        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT scheme_total_flats_positive CHECK (total_flats > 0),
    CONSTRAINT scheme_window_ordered       CHECK (applications_close_at > applications_open_at),
    CONSTRAINT scheme_status_known         CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'FROZEN', 'ALLOTTED'))
);

COMMENT ON TABLE  scheme                        IS 'A housing scheme: a fixed number of flats allocated by one published rule set.';
COMMENT ON COLUMN scheme.applications_close_at  IS 'Deadline for submission, not for data entry. A paper form handed in before this instant is on time even if it is typed in afterwards.';

-- ---------------------------------------------------------------------------
-- audit_event — append-only, hash-chained record of every decision-affecting act.
--
-- prev_hash/hash are computed in application code, not here, so that a third party can
-- recompute the chain in any language from the published fields. Doing it in a trigger
-- would make the chain a property of this database rather than of the published data.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
    seq           BIGSERIAL    PRIMARY KEY,
    event_id      UUID        NOT NULL DEFAULT gen_random_uuid(),
    occurred_at   TIMESTAMPTZ NOT NULL,
    actor         TEXT        NOT NULL,
    action        TEXT        NOT NULL,
    subject_type  TEXT        NOT NULL,
    subject_id    TEXT        NOT NULL,
    payload       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    prev_hash     CHAR(64)    NOT NULL,
    hash          CHAR(64)    NOT NULL,

    CONSTRAINT audit_event_hash_hex      CHECK (hash      ~ '^[0-9a-f]{64}$'),
    CONSTRAINT audit_event_prev_hash_hex CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT audit_event_hash_unique   UNIQUE (hash)
);

CREATE INDEX audit_event_subject_idx ON audit_event (subject_type, subject_id);
CREATE INDEX audit_event_action_idx  ON audit_event (action, occurred_at);

COMMENT ON TABLE  audit_event           IS 'Append-only hash chain. UPDATE, DELETE and TRUNCATE are refused by trigger.';
COMMENT ON COLUMN audit_event.prev_hash IS 'hash of seq-1; 64 zeroes for the genesis event.';

-- Append-only enforcement.
--
-- This is defence in depth, not a security boundary: anyone with DDL rights can drop the
-- trigger. What it buys is that tampering must be deliberate and leaves its own trace —
-- casual or accidental mutation is impossible, and the hash chain still catches the rest.
-- Phase 8 adds the second layer: revoking UPDATE/DELETE from the application role.
CREATE OR REPLACE FUNCTION audit_event_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only; % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation',
              HINT    = 'Corrections are recorded as new compensating events, never by editing history.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_mutation
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_append_only();
