-- V3 — deduplication.
--
-- "A fair number applied twice because they were not sure the first one went through."
--
-- Phase 1 stopped a retried HTTP request from becoming a second application. This phase deals
-- with the harder half: a person who genuinely filled the form in twice, on two days, possibly
-- through two different channels, and quite possibly spelling their own name differently the
-- second time.
--
-- The governing rule of this schema is that nothing is ever deleted. A duplicate application is
-- linked to its canonical sibling and stays in the register forever. Four thousand people
-- applied; the published result must account for all four thousand, and a row that quietly
-- vanished cannot be accounted for — least of all to the person who submitted it.

-- Trigram matching, for the fuzzy tier. Postgres ships it; it just needs enabling.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- application_fingerprint
--
-- The evidence, materialised. Each row says "this application carries this fingerprint at this
-- tier", and two applications sharing a fingerprint are candidates for merging.
--
-- These could be computed on the fly by grouping on the underlying columns, and that would be
-- less schema. They are stored because of the question this system exists to answer: when an
-- applicant asks "why did you decide my two applications were the same person?", the answer
-- should be a stored fact with a tier and a value, not a query that someone has to re-derive and
-- be trusted about.
-- ---------------------------------------------------------------------------
CREATE TABLE application_fingerprint (
    application_id  UUID NOT NULL REFERENCES application(id),
    tier            TEXT NOT NULL,
    fingerprint     TEXT NOT NULL,

    PRIMARY KEY (application_id, tier),
    CONSTRAINT application_fingerprint_tier_known
        CHECK (tier IN ('GOVERNMENT_ID', 'NAME_DOB_PHONE', 'NAME_DOB_EMAIL')),
    CONSTRAINT application_fingerprint_hex
        CHECK (fingerprint ~ '^[0-9a-f]{64}$')
);

-- The matching query joins fingerprint to fingerprint; this is the index that makes it a lookup.
CREATE INDEX application_fingerprint_lookup_idx ON application_fingerprint (tier, fingerprint);

COMMENT ON TABLE application_fingerprint IS 'Stored match evidence: which applications carry which fingerprint at which tier.';

-- ---------------------------------------------------------------------------
-- duplicate_link
--
-- One row per application that has been determined to be a duplicate of another. The canonical
-- application is the one the person is treated as having made; the duplicate remains readable,
-- linked, and excluded from the draw.
--
-- The unique constraint on duplicate_application_id is what keeps this a forest rather than a
-- tangle: an application is a duplicate of at most one canonical application, so following the
-- link always terminates.
-- ---------------------------------------------------------------------------
CREATE TABLE duplicate_link (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id                UUID        NOT NULL REFERENCES scheme(id),
    canonical_application_id UUID        NOT NULL REFERENCES application(id),
    duplicate_application_id UUID        NOT NULL REFERENCES application(id) UNIQUE,
    tier                     TEXT        NOT NULL,
    -- What matched, in enough detail to defend the decision without re-running anything.
    evidence                 JSONB       NOT NULL,
    -- 'system' for the automatic tiers; an operator id when a human confirmed it.
    decided_by               TEXT        NOT NULL,
    decided_at               TIMESTAMPTZ NOT NULL,

    CONSTRAINT duplicate_link_not_self
        CHECK (canonical_application_id <> duplicate_application_id),
    CONSTRAINT duplicate_link_tier_known
        CHECK (tier IN ('GOVERNMENT_ID', 'NAME_DOB_PHONE', 'NAME_DOB_EMAIL', 'PROBABLE'))
);

CREATE INDEX duplicate_link_canonical_idx ON duplicate_link (canonical_application_id);
CREATE INDEX duplicate_link_scheme_idx    ON duplicate_link (scheme_id);

COMMENT ON COLUMN duplicate_link.tier IS 'PROBABLE appears here only after a human confirmed it; the fuzzy tier never merges on its own.';

-- ---------------------------------------------------------------------------
-- duplicate_review
--
-- The fuzzy tier's output. A pair of applications that look like the same person but are not
-- provably so, waiting for a human.
--
-- The pair is stored with the lower id first and constrained unique, so the same two
-- applications can never be queued twice in opposite order — which matters because the
-- deduplication pass is re-runnable and would otherwise accumulate a new copy of every
-- undecided pair on each run.
--
-- Rejected pairs stay in this table precisely so that re-running does not raise them again.
-- A decision, once taken, is remembered.
-- ---------------------------------------------------------------------------
CREATE TABLE duplicate_review (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id          UUID        NOT NULL REFERENCES scheme(id),
    application_a_id   UUID        NOT NULL REFERENCES application(id),
    application_b_id   UUID        NOT NULL REFERENCES application(id),
    similarity         NUMERIC(4,3) NOT NULL,
    evidence           JSONB       NOT NULL,
    status             TEXT        NOT NULL,
    raised_at          TIMESTAMPTZ NOT NULL,
    decided_at         TIMESTAMPTZ,
    decided_by         TEXT,
    decision_note      TEXT,

    CONSTRAINT duplicate_review_pair_ordered
        CHECK (application_a_id < application_b_id),
    CONSTRAINT duplicate_review_pair_unique
        UNIQUE (application_a_id, application_b_id),
    CONSTRAINT duplicate_review_status_known
        CHECK (status IN ('PENDING', 'CONFIRMED_DUPLICATE', 'NOT_DUPLICATE')),
    CONSTRAINT duplicate_review_similarity_range
        CHECK (similarity >= 0 AND similarity <= 1),
    CONSTRAINT duplicate_review_decided_has_decider
        CHECK (status = 'PENDING' OR (decided_at IS NOT NULL AND decided_by IS NOT NULL))
);

CREATE INDEX duplicate_review_pending_idx ON duplicate_review (scheme_id, status, raised_at);

COMMENT ON TABLE duplicate_review IS 'Fuzzy matches awaiting a human decision. Nothing here is merged automatically.';
