/**
 * Works out which applications belong to the same person.
 *
 * <h2>Where to start</h2>
 *
 * <p>{@link com.zenalyst.housing.identity.DeduplicationService} is the entry point and reads top
 * to bottom as the whole algorithm. Everything else here is something it calls.
 *
 * <h2>How it works</h2>
 *
 * <ol>
 *   <li>{@link com.zenalyst.housing.identity.Fingerprints} reduces each application to one hash
 *       per {@link com.zenalyst.housing.identity.MatchTier}, and
 *       {@link com.zenalyst.housing.identity.FingerprintStore} stores them and finds the ones that
 *       collide. A collision means those two applications agree on everything that tier is made
 *       of.</li>
 *   <li>{@link com.zenalyst.housing.identity.MergeGraph} turns those pairwise matches into whole
 *       people. This is the part that is easy to get wrong: identity is transitive, and merging
 *       pairs independently would leave one person holding several entries in the draw.</li>
 *   <li>{@link com.zenalyst.housing.identity.FuzzyMatchFinder} finds applications that merely
 *       resemble each other. Nothing here is ever merged automatically; it becomes a
 *       {@link com.zenalyst.housing.identity.DuplicateReview} for a human.</li>
 *   <li>{@link com.zenalyst.housing.identity.DuplicateLink} records the conclusions.
 *       {@link com.zenalyst.housing.identity.IdentityService} answers the question an applicant
 *       actually asks: "does my application still count?"</li>
 * </ol>
 *
 * <h2>The two rules that govern everything here</h2>
 *
 * <p><strong>Nothing is deleted.</strong> A duplicate application stays in the register, linked
 * and readable, with a stored reason. Four thousand people applied and the published result must
 * account for all four thousand.
 *
 * <p><strong>Uncertainty is escalated, not resolved.</strong> The three exact tiers merge on their
 * own because they rest on something the applicant supplied deliberately. Resemblance is a
 * question for a person, and the answer is recorded with their name on it. See ADR-0006.
 */
package com.zenalyst.housing.identity;
