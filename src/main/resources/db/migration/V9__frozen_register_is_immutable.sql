-- V9 — the frozen register is immutable too.
--
-- Every other published artefact refuses to be edited: the audit chain, the allotments, the
-- rankings, the waitlists, a published draw. The frozen register did not, which was an oversight
-- rather than a decision.
--
-- The gap was not a hole — a tampered candidate row is caught immediately by
-- POST /draws/{id}/verify, because the stored rows no longer rebuild the published root. But
-- "detected afterwards" and "refused outright" are different guarantees, and the whole point of
-- the triggers elsewhere is that tampering has to be deliberate: somebody must knowingly drop a
-- trigger, which is itself a conspicuous act. A plain UPDATE should not have been enough here
-- either.
--
-- A freeze is a snapshot. There is no legitimate reason to amend one: the register is re-frozen
-- instead, producing a new root, and the difference between the two roots is public.
CREATE OR REPLACE FUNCTION frozen_register_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'the frozen register is a snapshot; % on % is not permitted', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation',
              HINT    = 'Freeze the register again to capture a correction; the new root differs and that difference is public.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER frozen_registry_no_mutation
    BEFORE UPDATE OR DELETE ON frozen_registry
    FOR EACH ROW EXECUTE FUNCTION frozen_register_append_only();

CREATE TRIGGER frozen_candidate_no_mutation
    BEFORE UPDATE OR DELETE ON frozen_candidate
    FOR EACH ROW EXECUTE FUNCTION frozen_register_append_only();
