# 5. The submission date is not the data-entry date

- **Status:** accepted
- **Phase:** 1

## Context

Applications arrive through two channels. Online submissions are received the moment they are
made. Paper forms are handed in at a counter and typed into the system later — sometimes days
later, and for a scheme with four thousand applicants, quite possibly weeks later for the forms
at the bottom of the pile.

A single `created_at` column collapses these two events into one. Under that model, a form handed
in on 3 March and typed up on 19 March is recorded as having been submitted on 19 March. If the
deadline was 14 March, the applicant is now disqualified — not by anything they did, but by how
quickly a clerk reached their form.

This is not a hypothetical class of bug. It is a systematic one: it penalises whoever was
processed last, which correlates with whoever applied at a busy counter, in a busy ward, near the
deadline. It would be discovered by an applicant who knows perfectly well when they applied, and
it would be indefensible.

## Decision

Two columns, with distinct meanings that are documented in the schema itself.

- `submitted_at` — when the applicant applied. For online, the instant of receipt. For paper, the
  date stamped on the counter receipt.
- `recorded_at` — when this row was written.

**Every deadline and eligibility-window check uses `submitted_at`.** `recorded_at` exists for
operational questions ("how far behind is data entry?") and for the audit trail, and never
decides anything about an applicant.

A database constraint enforces `submitted_at <= recorded_at`, since a form cannot be recorded
before it was submitted.

Paper rows additionally carry `paper_reference` and `entered_by`, and the database refuses a
paper row without both. A transcription dispute — "that is not what I wrote" — is answerable only
if the physical receipt can be located and the person who typed it identified.

Receipt dates are anchored at the start of the day in UTC. A counter stamps a day, not a time, and
the start of that day is the reading most favourable to an applicant whose form is dated the
deadline itself.

Both timestamps are returned to the applicant. Someone who applied on the 3rd is entitled to see
that the system knows it, even though it recorded them on the 19th.

## Consequences

**Gained.** The deadline means what applicants were told it means. The gap between the two
timestamps is visible, measurable, and auditable.

**Given up.** The paper endpoint can set a submission date in the past, which is a capability
nobody else may have. It is therefore a separate endpoint from online submission rather than a
parameter on a shared one — so that phase 8 secures it by putting an operator role on a single
route, with no possibility of the public route inheriting the privilege. Until then, the paper
import endpoint is unauthenticated and **must not be exposed outside a trusted network**.

**Still open.** The system trusts the receipt date the clerk types. Nothing here prevents a clerk
backdating a form. What it does provide is a record of who entered it, when they entered it, and
which physical receipt it claims to come from — which turns an undetectable act into an
investigable one.
