-- V4 — eligibility, and freezing the register.
--
-- Two things happen here, and the order matters. First the system works out, for every
-- application, whether it competes and on what terms. Then it takes an immutable, hashed snapshot
-- of those conclusions.
--
-- The snapshot is what the draw runs against. Everything after this point — the lottery, the
-- allotments, the published list — is a function of one frozen registry and a seed. That is what
-- makes "run it yourself and check" a real offer rather than a slogan.

-- ---------------------------------------------------------------------------
-- claim_verification
--
-- Applicants declare things about themselves that change which pool they compete in: their
-- category, whether they live in the area, whether they have a disability, whether they served.
-- Those are claims, not facts, and the difference has to be visible in the data.
--
-- An unverified claim is NOT a disqualification. Someone whose category certificate has not been
-- checked still competes — on open merit, without the reservation they claimed. Treating an
-- unverified claim as a lie would punish applicants for their own authority's backlog.
-- ---------------------------------------------------------------------------
CREATE TABLE claim_verification (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id      UUID        NOT NULL REFERENCES application(id),
    claim               TEXT        NOT NULL,
    outcome             TEXT        NOT NULL,
    -- Which document was seen. Without it the verification is one person's recollection.
    evidence_reference  TEXT,
    note                TEXT,
    verified_by         TEXT        NOT NULL,
    verified_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT claim_verification_one_per_claim
        UNIQUE (application_id, claim),
    CONSTRAINT claim_verification_claim_known
        CHECK (claim IN ('CATEGORY', 'LOCAL_RESIDENCE', 'DISABILITY', 'EX_SERVICE', 'INCOME')),
    CONSTRAINT claim_verification_outcome_known
        CHECK (outcome IN ('VERIFIED', 'REJECTED'))
);

CREATE INDEX claim_verification_application_idx ON claim_verification (application_id);

COMMENT ON TABLE claim_verification IS 'Operator decisions on declared attributes. Absent means unverified, which costs the applicant the benefit but never their place.';

-- ---------------------------------------------------------------------------
-- frozen_registry
--
-- One row per freeze: the moment the inputs to a draw stopped moving.
--
-- registry_root is a Merkle root over every candidate row. Publishing it commits the authority to
-- an exact set of candidates with exact attributes, before any seed is revealed. Nobody can
-- afterwards add a candidate, remove one, or quietly change somebody's category without the root
-- changing — and the root is already in the newspaper.
-- ---------------------------------------------------------------------------
CREATE TABLE frozen_registry (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id        UUID        NOT NULL REFERENCES scheme(id),
    registry_root    TEXT        NOT NULL,
    candidate_count  INTEGER     NOT NULL,
    eligible_count   INTEGER     NOT NULL,
    -- The eligibility thresholds in force. A root proves which candidates were frozen; this
    -- proves the rules they were judged by, so "you changed the income limit afterwards" is
    -- answerable.
    rules_hash       TEXT        NOT NULL,
    rules_document   JSONB       NOT NULL,
    frozen_at        TIMESTAMPTZ NOT NULL,
    frozen_by        TEXT        NOT NULL,

    CONSTRAINT frozen_registry_root_hex     CHECK (registry_root ~ '^[0-9a-f]{64}$'),
    CONSTRAINT frozen_registry_rules_hex    CHECK (rules_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT frozen_registry_counts_sane  CHECK (candidate_count >= 0 AND eligible_count BETWEEN 0 AND candidate_count)
);

CREATE INDEX frozen_registry_scheme_idx ON frozen_registry (scheme_id, frozen_at DESC);
CREATE INDEX frozen_registry_root_idx   ON frozen_registry (registry_root);

-- Deliberately NOT unique on registry_root. Freezing unchanged data twice produces the same root,
-- and that is the property being relied on — two freezes are two events with one conclusion.

-- ---------------------------------------------------------------------------
-- frozen_candidate
--
-- The snapshot itself: one row per application, carrying exactly the attributes the draw will
-- consume and nothing else.
--
-- canonical_json is stored verbatim because it is the preimage of leaf_hash. A verifier does not
-- have to trust our re-derivation of a candidate's attributes; they can hash the stored bytes and
-- check them against the published root themselves.
--
-- Names, addresses and contact details are absent on purpose. This table is meant to be
-- publishable, and none of it decides who gets a flat.
-- ---------------------------------------------------------------------------
CREATE TABLE frozen_candidate (
    registry_id                UUID    NOT NULL REFERENCES frozen_registry(id),
    leaf_index                 INTEGER NOT NULL,
    application_no             TEXT    NOT NULL,
    leaf_hash                  TEXT    NOT NULL,
    canonical_json             TEXT    NOT NULL,

    eligible                   BOOLEAN NOT NULL,
    -- The category the applicant will actually compete in: their claim if it was verified,
    -- otherwise GEN. Kept separate from the declared category so both remain answerable.
    effective_category         TEXT    NOT NULL,
    effective_local_resident   BOOLEAN NOT NULL,
    effective_disability       BOOLEAN NOT NULL,
    effective_ex_serviceperson BOOLEAN NOT NULL,
    gender                     TEXT    NOT NULL,

    PRIMARY KEY (registry_id, leaf_index),
    CONSTRAINT frozen_candidate_unique_application UNIQUE (registry_id, application_no),
    CONSTRAINT frozen_candidate_leaf_hex     CHECK (leaf_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT frozen_candidate_category_known
        CHECK (effective_category IN ('GEN', 'SC', 'ST', 'OBC', 'EWS')),
    CONSTRAINT frozen_candidate_gender_known
        CHECK (gender IN ('FEMALE', 'MALE', 'OTHER', 'UNDISCLOSED'))
);

CREATE INDEX frozen_candidate_lookup_idx ON frozen_candidate (registry_id, application_no);

COMMENT ON COLUMN frozen_candidate.canonical_json IS 'The exact bytes hashed into leaf_hash. Published, so anyone can recompute the root.';
