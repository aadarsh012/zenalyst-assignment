# 3. National identity numbers are never stored

- **Status:** accepted
- **Phase:** 1

## Context

Deduplication needs to recognise that two applications belong to the same person, and the
national identity number is by far the strongest signal available: exact, deliberate, and
supplied by the applicant themselves.

The obvious implementation stores the number and indexes it. That produces a searchable register
of four thousand national identity numbers, held by a housing authority, for the purpose of
running one draw. The number will outlive the draw, and everything that ever happens to that
database — a backup on a laptop, a support engineer with read access, a misconfigured replica —
now involves identity numbers.

What deduplication actually needs is narrower than what storing the number provides. It needs to
answer "are these two the same person?". It does not need to answer "who is this?".

## Decision

The number is accepted, validated, used to derive a token, and then discarded. It is never
written to disk.

What is persisted is `HMAC-SHA256(pepper, schemeCode ‖ number)`, plus the last four digits so a
counter clerk can confirm identity with an applicant in person. The submitted payload is
preserved verbatim in `raw_payload` with this one field replaced by `<redacted>`.

The pepper is supplied as configuration and deliberately kept out of the database, so that a
database compromise on its own does not yield the numbers. The application refuses to start if
the development pepper is still in place under a non-development profile.

Scoping the token by scheme code means the same person applying to two schemes produces two
unrelated tokens, so the tokens cannot be joined into a profile of an individual across schemes.

Numbers are validated on arrival with their Verhoeff check digit, which catches every
single-digit error and every adjacent transposition — the two mistakes a clerk copying twelve
digits off a paper form actually makes. Rejecting a mistyped number at the counter is far better
than discovering weeks later that someone failed to deduplicate against their own application.

## Consequences

**Gained.** The system cannot leak what it does not hold. Deduplication is unaffected: equality
of tokens is exactly equality of numbers. A challenge over identity remains answerable — hash the
number the applicant produces and compare it with the stored token.

**Given up.** `raw_payload` is no longer a byte-exact copy of the submission; one field is
redacted. That is a real loss for a system whose defence is "here is exactly what we received",
and it is accepted because the alternative is worse. Nothing else about the submission is
touched.

Also given up: any future need to *display* the number, or to reconcile against an external
register that supplies numbers rather than tokens, would require re-collecting it.

**What this does not protect against.** The number space is 10¹², which is enumerable. An
attacker holding both the database and the pepper recovers every number; the pepper is the whole
of the secret. Keeping it out of the database raises the bar to compromising two things instead
of one, and no further. A production system handling real identity numbers should hold the key in
an HSM or KMS so that the application process never sees it, and should rate-limit token
derivation. That is a deployment decision, not a code change, but it should be made before this
system holds a real number.
