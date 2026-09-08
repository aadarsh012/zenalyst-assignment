# 6. Fuzzy matches are never merged automatically

- **Status:** accepted
- **Phase:** 2

## Context

Deduplication matches at four tiers. Three of them rest on an exact match of something the
applicant supplied deliberately — the same identity number, or the same name, birthday and phone
number. The fourth rests on two strings looking alike: trigram similarity over normalised names,
where the dates of birth agree.

The fourth tier is where the remaining duplicates are. "Ramesh Chandra Kumar" and "Ramesh Chandra
Kumarr" with the same birthday, one applying online and one on paper with a different phone
number, is a real person applying twice and no exact tier will ever catch them.

It is also where the mistakes are. Two brothers with similar names born on the same day. A common
name in a large ward. A clerk who transcribed a name badly enough to resemble somebody else's.

## Decision

Similarity never merges anything. A fuzzy match becomes a `duplicate_review` and waits for a
person.

The threshold is 0.80 and configurable. It sits where it does because the two failure modes are
not symmetric: a false positive costs an operator ten seconds looking at two names, and a false
negative costs a citizen who applied once being matched against a stranger — or a person holding
two entries in a draw that allows one per household.

Decisions are recorded permanently, both ways. A rejection is kept precisely so that re-running
does not put the same pair in front of the same operator next week until somebody gives in and
confirms it. Reviews cannot be decided twice; a second attempt is refused rather than allowed to
overwrite the first, because two operators disagreeing is exactly what an applicant contesting the
outcome would want to know about.

Reviews are raised **per pair of people, not per pair of applications**. Someone mistyping their
name on a fourth application resembles all three earlier ones equally; queued naively that is
three rows asking one question, which an operator may well answer three times and possibly
differently.

## Consequences

**Gained.** No application is ever set aside because two strings looked alike. Every merge is
either provable from an exact field match or attributable to a named person with a written reason
and a timestamp. That is the difference between a decision that can be defended and one that can
only be asserted.

**Given up.** Deduplication is not fully automatic, and a scheme with four thousand applicants will
generate a review queue that somebody has to work through before the draw. That is real
operational cost, and it is the correct place to spend it: the alternative is a system that
silently discards citizens' applications on a similarity score, which is precisely what loses in
court.

**Not addressed.** The threshold is a single global number. A name common in one ward is not
common in another, and a smarter system would weight rarity. That would improve the queue's
signal-to-noise; it would not change this decision, since the output would still be a suggestion
for a human.
