-- V2 — application intake.
--
-- Two tables: the applications themselves, and the idempotency ledger that stops a nervous
-- applicant's second click from becoming a second application.

-- Fixed-width identifiers are TEXT with a CHECK, not CHAR(n). In PostgreSQL char(n) is
-- blank-padded and carries no performance advantage over text, and it reports itself as bpchar,
-- which an ORM validating a String field rejects. The CHECK constraints below pin the exact
-- length and alphabet far more precisely than a type declaration would.
--
-- V1 declared audit_event's hashes as CHAR(64). That migration has been applied and is therefore
-- not edited (ADR-0001); no entity maps those columns, so nothing depends on the difference.

CREATE SEQUENCE application_no_seq START 1;

CREATE TABLE application (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id              UUID        NOT NULL REFERENCES scheme(id),
    application_no         TEXT        NOT NULL UNIQUE,
    channel                TEXT        NOT NULL,

    -- Exactly what arrived, before we touched it. Every normalised column below is derived
    -- from this and can be re-derived from it; if our parsing is ever challenged, this is the
    -- evidence of what the applicant actually wrote.
    --
    -- The government id is the one field NOT preserved here — see government_id_hash.
    raw_payload            JSONB       NOT NULL,

    -- Identity, as given and as normalised. Both are kept: the given form is what we show the
    -- applicant and what a court would expect to see, the normalised form is what we match on.
    full_name              TEXT        NOT NULL,
    name_key               TEXT        NOT NULL,
    date_of_birth          DATE        NOT NULL,
    phone_e164             TEXT,
    email                  TEXT,
    email_key              TEXT,
    address_line           TEXT        NOT NULL,
    ward_code              TEXT,

    -- The government id is never stored in the clear. A salted hash is enough to detect that
    -- two applications are the same person, which is the only thing we need it for; holding
    -- 4,000 national identity numbers is a liability with no corresponding benefit.
    -- Last four digits are retained so a counter clerk can confirm identity with an applicant.
    government_id_hash     TEXT        NOT NULL,
    government_id_last4    TEXT        NOT NULL,

    -- Declared attributes. These drive quota placement later; at intake they are recorded as
    -- claimed, not as verified. Verification is a separate, auditable step.
    category               TEXT        NOT NULL,
    gender                 TEXT        NOT NULL,
    is_local_resident      BOOLEAN     NOT NULL DEFAULT false,
    has_disability         BOOLEAN     NOT NULL DEFAULT false,
    is_ex_serviceperson    BOOLEAN     NOT NULL DEFAULT false,
    annual_income          NUMERIC(12,2),

    -- The two dates that are emphatically not the same thing.
    --
    -- submitted_at is when the applicant applied — for online, when we received it; for paper,
    -- when the form was handed in at the counter. recorded_at is when it entered this database.
    -- For paper applications those can be weeks apart, and confusing them would disqualify
    -- people who applied on time because a clerk was slow. Deadline checks use submitted_at.
    submitted_at           TIMESTAMPTZ NOT NULL,
    recorded_at            TIMESTAMPTZ NOT NULL,

    -- Provenance for paper applications: which physical receipt this came from, and who typed
    -- it in. A transcription dispute is answerable only if both are recorded.
    paper_reference        TEXT,
    entered_by             TEXT,

    CONSTRAINT application_channel_known
        CHECK (channel IN ('ONLINE', 'PAPER')),
    CONSTRAINT application_category_known
        CHECK (category IN ('GEN', 'SC', 'ST', 'OBC', 'EWS')),
    CONSTRAINT application_gender_known
        CHECK (gender IN ('FEMALE', 'MALE', 'OTHER', 'UNDISCLOSED')),
    CONSTRAINT application_govt_id_hash_hex
        CHECK (government_id_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT application_govt_id_last4_digits
        CHECK (government_id_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT application_income_non_negative
        CHECK (annual_income IS NULL OR annual_income >= 0),
    CONSTRAINT application_submitted_before_recorded
        CHECK (submitted_at <= recorded_at),
    -- A paper application without provenance cannot be defended if its contents are disputed,
    -- so the database refuses to hold one.
    CONSTRAINT application_paper_has_provenance
        CHECK (channel <> 'PAPER' OR (paper_reference IS NOT NULL AND entered_by IS NOT NULL))
);

-- A paper receipt is imported at most once. When a clerk fixes three bad rows and re-uploads
-- the whole file, the rows that already landed must not land again — otherwise the import tool
-- becomes a duplicate generator, which is precisely the problem this system exists to solve.
CREATE UNIQUE INDEX application_paper_reference_idx
    ON application (scheme_id, paper_reference)
    WHERE paper_reference IS NOT NULL;

-- Tier 1 deduplication looks up by government id hash; tier 2 and 3 by name and date of birth.
CREATE INDEX application_govt_id_hash_idx ON application (scheme_id, government_id_hash);
CREATE INDEX application_name_dob_idx     ON application (scheme_id, name_key, date_of_birth);
CREATE INDEX application_phone_idx        ON application (scheme_id, phone_e164) WHERE phone_e164 IS NOT NULL;
CREATE INDEX application_email_key_idx    ON application (scheme_id, email_key)  WHERE email_key IS NOT NULL;
CREATE INDEX application_submitted_at_idx ON application (scheme_id, submitted_at, application_no);

COMMENT ON TABLE  application                     IS 'One submitted application. Duplicates are expected and are never deleted; they are linked during deduplication.';
COMMENT ON COLUMN application.raw_payload         IS 'The submission exactly as received, government id redacted.';
COMMENT ON COLUMN application.submitted_at        IS 'When the applicant applied. For paper, the counter receipt date, not the data-entry date.';
COMMENT ON COLUMN application.recorded_at         IS 'When this row was written. For paper, later than submitted_at.';
COMMENT ON COLUMN application.government_id_hash  IS 'sha256(normalised id || scheme salt). The id itself is never stored.';

-- ---------------------------------------------------------------------------
-- idempotency_record
--
-- "A fair number applied twice because they were not sure the first one went through" is in
-- the brief. Some of those are genuine duplicate applications and belong to deduplication.
-- But a double-submitted form, a retried request after a timeout, or a double-clicked button
-- is not a second application, and it should never become one.
--
-- The client supplies an Idempotency-Key; the same key replays the original response rather
-- than creating a second row.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_record (
    idempotency_key   TEXT        PRIMARY KEY,
    endpoint          TEXT        NOT NULL,
    -- Hash of the request body. The same key with a different body is a client bug, not a
    -- retry, and must be rejected rather than silently answered with the wrong response.
    request_hash      TEXT        NOT NULL,
    response_status   INTEGER     NOT NULL,
    -- TEXT, deliberately not JSONB. A replayed request must return the original response byte
    -- for byte; jsonb reorders keys and discards whitespace, so storing it as jsonb would hand
    -- a retrying client a subtly different document from the one its first attempt received.
    -- Nothing needs to query inside this column, so the structure buys nothing.
    response_body     TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_request_hash_hex
        CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE idempotency_record IS 'Replay ledger for POST endpoints. A repeated Idempotency-Key returns the original response instead of acting twice.';
