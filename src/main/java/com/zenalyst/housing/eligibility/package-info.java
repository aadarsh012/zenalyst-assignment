/**
 * Decides whether an application competes for a flat, and on what terms.
 *
 * <h2>Where to start</h2>
 *
 * <p>{@link com.zenalyst.housing.eligibility.EligibilityEvaluator} — pure, no database, and the
 * whole of the rules. {@link com.zenalyst.housing.eligibility.EligibilityService} exists only to
 * assemble its inputs and record what operators decide.
 *
 * <h2>The distinction this package is built around</h2>
 *
 * <p><strong>Disqualifying rules</strong> remove an applicant from the draw. There are two: being
 * under age, and being a duplicate of another application. Both rest on something objective.
 *
 * <p><strong>Claim rules</strong> decide which pool an applicant competes in. Failing one is not a
 * disqualification — an unverified category certificate means competing on open merit, an
 * unverified residence proof means no local preference. The applicant still competes.
 *
 * <p>That asymmetry is deliberate and load-bearing. Treating an unverified claim as a failed one
 * would throw out applicants whose only fault was that their certificate had not reached the top
 * of an official's pile, and the scheme would then be allocating flats partly on the basis of its
 * own administrative backlog.
 *
 * <p>Every decision is a list of checks with codes and plain-English detail, never a bare boolean.
 * An applicant told only "not eligible" has nothing to check, nothing to correct, and nothing to
 * appeal against.
 */
package com.zenalyst.housing.eligibility;
