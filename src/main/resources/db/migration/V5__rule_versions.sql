-- V5 — versioned rule documents.
--
-- The quota matrix is not configuration. It is a published instrument that decides who gets a
-- flat, it changes between schemes and sometimes within one, and every allotment has to be
-- attributable to the exact version in force when it was drawn.
--
-- Keeping it in application.yml would mean a result whose rules could be edited afterwards with
-- nothing to show it had happened. Keeping it here, versioned and hashed, means a published
-- allotment carries proof of the matrix that produced it.

CREATE TABLE rule_version (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id       UUID        NOT NULL REFERENCES scheme(id),
    version         TEXT        NOT NULL,
    rules_hash      TEXT        NOT NULL,
    -- The canonical bytes that were hashed, stored verbatim. A verifier hashes these rather than
    -- re-serialising our object graph and hoping it matches.
    rules_document  JSONB       NOT NULL,
    status          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      TEXT        NOT NULL,
    activated_at    TIMESTAMPTZ,
    activated_by    TEXT,

    CONSTRAINT rule_version_unique_per_scheme UNIQUE (scheme_id, version),
    CONSTRAINT rule_version_status_known      CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT rule_version_hash_hex          CHECK (rules_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rule_version_activated_together
        CHECK (status = 'DRAFT' OR (activated_at IS NOT NULL AND activated_by IS NOT NULL))
);

-- At most one active rule version per scheme, enforced by the database rather than by whichever
-- service method happens to run. "Which rules were in force?" must have exactly one answer.
CREATE UNIQUE INDEX rule_version_single_active_idx
    ON rule_version (scheme_id)
    WHERE status = 'ACTIVE';

CREATE INDEX rule_version_scheme_idx ON rule_version (scheme_id, created_at DESC);

COMMENT ON TABLE rule_version IS 'The published quota matrix, versioned and hashed. Superseded versions are kept: a past allotment must remain explicable.';
