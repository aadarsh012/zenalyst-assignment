# 15. Restrict what influences the outcome; leave open what checks it

- **Status:** accepted
- **Phase:** 8

## Context

Six phases of work rest on a claim: that anyone can re-derive this system's published results
without trusting the authority that produced them. Adding authentication is where that claim is
most easily destroyed, and destroyed by accident — the natural instinct is to secure everything,
and securing the verification endpoints would quietly convert every published proof into a promise.

At the same time several endpoints genuinely cannot stay open. One in particular: the paper-import
route is the only path that may set a submission date in the past (ADR-0005). Unsecured, any member
of the public can post a backdated application and claim a deadline they missed.

## Decision

One rule, applied throughout: **everything that can influence the outcome is restricted; everything
that can check it is open.**

Open, deliberately and permanently: verifying a draw, verifying the audit chain, downloading the
published register or the results file, reading a scheme or a draw. Also applying, and filing an
objection — both things a member of the public does, and neither able to influence an outcome on
its own. A verification endpoint that requires the authority's own credentials proves nothing, so
the journalist rebuilding the Merkle root and the court re-deriving the draw need no account.

Restricted by role: `OPERATOR` records facts about applicants — typing up paper forms, verifying
certificates, judging fuzzy duplicate matches. `ADMIN` decides outcomes — the quota matrix, the
freeze, the draw, adjudicating objections. `AUDITOR` reads any applicant's file and changes
nothing.

**`/explain` is authorised per application, not merely by role.** An applicant's token carries their
application number as its subject, and it is compared against the file requested. This is the JWT
principal doing real work rather than proving somebody logged in. It is the one transparency
endpoint that is not public: the response carries no name or address, but it does carry a category
and the reasons somebody was found ineligible, and application numbers are sequential — leaving it
open would let anybody walk the register and assemble exactly the profile the rest of the system
takes care not to store.

**Stateless.** No sessions, no cookies, so no CSRF token: CSRF protection defends browser sessions
against cross-site posts, and with no session to ride on there is nothing to defend.

**The development token endpoint is confined by `@Profile("dev")`,** not by a configuration flag.
Under any other profile the bean does not exist and the route returns 404. There is no setting to
get wrong and no default that quietly leaves it enabled.

**Both development secrets refuse to start outside development.** The JWT signing secret and the
identity pepper each have a working local default and each throw on startup under a production
profile. The failure this prevents is not exotic: a default secret gets deployed unchanged more
often than anyone admits, and the symptom is nothing at all — the system works perfectly, and
anybody who has read the repository can mint an administrator token.

## Consequences

**Gained.** The verification story survives authentication intact. The one endpoint that can
backdate a submission is closed. An applicant can read their own file and no one else's.

**Given up.** Roles are coarse. A real authority distinguishes a counter clerk from a verification
officer from a district registrar, and would want an approval step on the draw rather than a single
`ADMIN` role that can commit, reveal, execute and publish alone. Four roles is enough to demonstrate
that authorisation is modelled rather than decorative, and not enough for a real deployment.

**Deliberately not done: rate limiting.** An in-process limiter across several instances limits
nothing except the instance a request happens to reach, while looking in code review as though the
problem were solved. Public intake endpoints do need one, and it belongs at the gateway or in shared
state — not here, half-implemented.

**Also not done: applicant identity.** There is no mechanism by which a real applicant obtains a
token bearing their own application number; the development endpoint issues one to anybody who asks.
A deployment integrates an identity provider, and the resource-server configuration is already
pointed at a decoder that would be swapped for its JWKS.
