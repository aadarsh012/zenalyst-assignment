/**
 * The published quota matrix, versioned and hashed.
 *
 * <h2>Where to start</h2>
 *
 * <p>{@link com.zenalyst.housing.rules.RuleService}. The rules themselves — what a quota matrix
 * means and how it is applied — live in {@code allocation}; this package only stores, versions and
 * validates them.
 *
 * <h2>Why this is not configuration</h2>
 *
 * <p>The matrix decides who gets a flat. In a properties file it could be edited after a draw with
 * nothing to show it had happened. Versioned and hashed, every allotment carries proof of the exact
 * matrix that produced it, and superseded versions are kept so that past draws remain explicable.
 *
 * <p>At most one version is active per scheme, enforced by a partial unique index rather than by
 * service logic: "which rules were in force?" must have exactly one answer.
 *
 * <p>Validation happens at creation. A matrix whose horizontal reservations exceed a pool, or whose
 * seats do not total the scheme's flats, is refused when it is written — not discovered mid-draw
 * with the seed already public.
 */
package com.zenalyst.housing.rules;
