-- V6 — the draw.
--
-- Everything before this phase existed to make this table defensible. A draw binds together a
-- frozen candidate register, a published quota matrix, and a seed that was committed to before
-- anybody could know what it would produce. Given those three, the six hundred names follow by
-- arithmetic.
--
-- JobRunr keeps its own tables. They live in a separate schema so that the boundary is visible:
-- everything in `public` is ours and is Flyway's (ADR-0001); everything in `jobrunr` belongs to a
-- library that versions and migrates its own storage, exactly as Flyway does with
-- flyway_schema_history.
CREATE SCHEMA IF NOT EXISTS jobrunr;

CREATE TABLE draw (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id         UUID        NOT NULL REFERENCES scheme(id),

    -- What the draw runs on. Captured at commit time, so the inputs cannot drift underneath it.
    registry_id       UUID        NOT NULL REFERENCES frozen_registry(id),
    registry_root     TEXT        NOT NULL,
    rule_version_id   UUID        NOT NULL REFERENCES rule_version(id),
    rules_hash        TEXT        NOT NULL,

    -- Where the randomness comes from, and what was published in advance about it.
    --
    -- AUTHORITY_COMMITTED: the authority generates a seed and salt, publishes
    --   SHA-256(seed || salt), and reveals the pair afterwards.
    -- DRAND_BEACON: the authority publishes a future round number of a public randomness beacon.
    --   It cannot know that round's value, which closes the hole the first option leaves open —
    --   see ADR-0011.
    seed_source       TEXT        NOT NULL,
    seed_commitment   TEXT,
    beacon_round      BIGINT,

    -- Revealed only after the commitment is public.
    seed              TEXT,
    seed_salt         TEXT,
    revealed_at       TIMESTAMPTZ,

    status            TEXT        NOT NULL,
    seats_awarded     INTEGER,
    -- Hash of the allocation result, so that a published outcome can be compared against a
    -- recomputation without diffing six hundred rows by eye.
    result_hash       TEXT,

    committed_at      TIMESTAMPTZ NOT NULL,
    committed_by      TEXT        NOT NULL,
    executed_at       TIMESTAMPTZ,
    published_at      TIMESTAMPTZ,
    published_by      TEXT,
    failure_reason    TEXT,

    CONSTRAINT draw_status_known
        CHECK (status IN ('COMMITTED', 'REVEALED', 'RUNNING', 'COMPLETED', 'PUBLISHED', 'FAILED')),
    CONSTRAINT draw_seed_source_known
        CHECK (seed_source IN ('AUTHORITY_COMMITTED', 'DRAND_BEACON')),
    -- Exactly one form of commitment, matching the source. A draw that committed to nothing is
    -- a draw whose seed could have been chosen after the fact.
    CONSTRAINT draw_commitment_matches_source CHECK (
        (seed_source = 'AUTHORITY_COMMITTED' AND seed_commitment IS NOT NULL AND beacon_round IS NULL)
     OR (seed_source = 'DRAND_BEACON'        AND beacon_round IS NOT NULL AND seed_commitment IS NULL)),
    CONSTRAINT draw_revealed_has_seed
        CHECK (status = 'COMMITTED' OR status = 'FAILED' OR (seed IS NOT NULL AND revealed_at IS NOT NULL)),
    CONSTRAINT draw_published_has_publisher
        CHECK (status <> 'PUBLISHED' OR (published_at IS NOT NULL AND published_by IS NOT NULL))
);

CREATE INDEX draw_scheme_idx ON draw (scheme_id, committed_at DESC);

-- At most one draw in flight per scheme. Two draws running at once against the same register is
-- not a scenario with a defensible answer: whichever finished second would silently become the
-- result, and nobody could say why.
CREATE UNIQUE INDEX draw_single_in_flight_idx
    ON draw (scheme_id)
    WHERE status IN ('COMMITTED', 'REVEALED', 'RUNNING');

-- ---------------------------------------------------------------------------
-- The result. Written once, by the execution job, inside one transaction.
-- ---------------------------------------------------------------------------
CREATE TABLE allotment (
    draw_id             UUID    NOT NULL REFERENCES draw(id),
    application_no      TEXT    NOT NULL,
    pool                TEXT    NOT NULL,
    basis               TEXT    NOT NULL,
    horizontal_category TEXT,
    pool_rank           INTEGER NOT NULL,
    overall_rank        INTEGER NOT NULL,
    ticket              TEXT    NOT NULL,

    PRIMARY KEY (draw_id, application_no),
    CONSTRAINT allotment_basis_known CHECK (basis IN ('MERIT', 'HORIZONTAL_TOP_UP')),
    CONSTRAINT allotment_top_up_names_its_reason
        CHECK (basis <> 'HORIZONTAL_TOP_UP' OR horizontal_category IS NOT NULL)
);

CREATE INDEX allotment_pool_idx ON allotment (draw_id, pool, pool_rank);

-- The full merit order, winners and losers alike. Three and a half thousand people will not get a
-- flat and each is entitled to know where they came; that is unanswerable from a list of six
-- hundred names.
CREATE TABLE draw_ranking (
    draw_id        UUID    NOT NULL REFERENCES draw(id),
    application_no TEXT    NOT NULL,
    overall_rank   INTEGER NOT NULL,
    ticket         TEXT    NOT NULL,

    PRIMARY KEY (draw_id, application_no),
    CONSTRAINT draw_ranking_unique_rank UNIQUE (draw_id, overall_rank)
);

-- So a surrender has an unarguable successor.
CREATE TABLE draw_waitlist (
    draw_id        UUID    NOT NULL REFERENCES draw(id),
    pool           TEXT    NOT NULL,
    position       INTEGER NOT NULL,
    application_no TEXT    NOT NULL,

    PRIMARY KEY (draw_id, pool, position)
);

CREATE INDEX draw_waitlist_application_idx ON draw_waitlist (draw_id, application_no);

CREATE TABLE draw_pool (
    draw_id          UUID    NOT NULL REFERENCES draw(id),
    pool             TEXT    NOT NULL,
    seats            INTEGER NOT NULL,
    awarded          INTEGER NOT NULL,
    competitors      INTEGER NOT NULL,
    cutoff_pool_rank INTEGER NOT NULL,
    horizontal       JSONB   NOT NULL,
    displaced        JSONB   NOT NULL,

    PRIMARY KEY (draw_id, pool)
);

-- ---------------------------------------------------------------------------
-- Results are written once and never edited.
--
-- The execution job writes every row of a draw in one transaction, so a failure leaves nothing
-- behind and a retry starts clean. There is therefore no legitimate reason to update or delete an
-- allotment, and the database refuses to — the same defence, and for the same reason, as
-- audit_event.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION draw_result_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'draw results are written once; % on % is not permitted', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation',
              HINT    = 'A mistaken draw is superseded by a new one, never edited.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER allotment_no_mutation
    BEFORE UPDATE OR DELETE ON allotment
    FOR EACH ROW EXECUTE FUNCTION draw_result_append_only();

CREATE TRIGGER draw_ranking_no_mutation
    BEFORE UPDATE OR DELETE ON draw_ranking
    FOR EACH ROW EXECUTE FUNCTION draw_result_append_only();

CREATE TRIGGER draw_waitlist_no_mutation
    BEFORE UPDATE OR DELETE ON draw_waitlist
    FOR EACH ROW EXECUTE FUNCTION draw_result_append_only();

CREATE TRIGGER draw_pool_no_mutation
    BEFORE UPDATE OR DELETE ON draw_pool
    FOR EACH ROW EXECUTE FUNCTION draw_result_append_only();

-- A published draw is final. Its status column may still be read, but nothing about it may change.
CREATE OR REPLACE FUNCTION draw_published_is_final() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'draw % is published and cannot be changed', OLD.id
            USING ERRCODE = 'restrict_violation',
                  HINT    = 'Supersede it with a new draw; a published allotment is not edited.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER draw_published_immutable
    BEFORE UPDATE OR DELETE ON draw
    FOR EACH ROW EXECUTE FUNCTION draw_published_is_final();

COMMENT ON TABLE draw IS 'One draw: a frozen register, a published rule version, and a seed committed to before it was known.';
