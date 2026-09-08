/**
 * Challenges to a published result, and the only sanctioned route to changing one.
 *
 * <h2>Where to start</h2>
 *
 * <p>{@link com.zenalyst.housing.objection.ObjectionService}.
 *
 * <h2>The rule everything here follows</h2>
 *
 * <p><strong>Upholding an objection changes nothing by itself.</strong> It records a finding, states
 * a remedy, and authorises a superseding draw. The correction to the underlying record — verifying a
 * certificate that was wrongly refused, rejecting a duplicate link that joined two different people
 * — happens through the ordinary endpoints, each leaving its own audit trail. The new draw is a
 * further deliberate act after that.
 *
 * <p>An "uphold" button that silently corrected records and reissued a list would be a single call
 * that rewrote a public lottery, with no evidence of what it had done beyond whatever it chose to
 * log.
 *
 * <h2>Re-drawing</h2>
 *
 * <p>A published draw is never edited — it cannot be; the database refuses. A corrected result is a
 * <em>new</em> draw that supersedes it, with its own seed, its own registry root and its own audit
 * trail. The original stays published and verifiable, so anybody can compare the two and see
 * exactly what changed.
 *
 * <p>Superseding requires an upheld objection. An authority able to re-draw at will could draw
 * repeatedly until it liked the answer, and the commitment ceremony would not catch it — every
 * individual draw would verify perfectly.
 */
package com.zenalyst.housing.objection;
