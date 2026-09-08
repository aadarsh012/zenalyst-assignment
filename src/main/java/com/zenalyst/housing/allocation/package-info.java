/**
 * Allocates the flats. Pure, and the reason the rest of the system exists.
 *
 * <h2>Where to start</h2>
 *
 * <p>{@link com.zenalyst.housing.allocation.Allocator} — one method, and the whole algorithm reads
 * top to bottom.
 *
 * <h2>Purity</h2>
 *
 * <p>Nothing here touches Spring, the database, the clock, or any randomness other than the seed.
 * That is enforced by {@code ArchitectureTest}, not by convention: the entire defence of this
 * system is that a third party can re-run the draw and get the same six hundred names, and a
 * single stray {@code Instant.now()} would falsify it while every other test kept passing.
 *
 * <h2>The two rules that make this hard</h2>
 *
 * <p><strong>Migration.</strong> The open pool is filled first, from everybody. A reserved-category
 * candidate who wins an open seat on merit consumes an open seat, and their category's reserved
 * seats stay available to others. Filling the reserved pools first would let a strong SC candidate
 * use up an SC seat a weaker SC candidate needed — turning a reservation into a ceiling.
 *
 * <p><strong>Horizontal reservations are carved out of a pool, not added to it.</strong> Thirty per
 * cent of a hundred seats for women means thirty of those hundred, not thirty more. Where too few
 * qualifying candidates make the merit cutoff, the shortfall displaces the lowest-ranked selectees
 * — and the displaced applicants are named in the result, because they have the strongest reason of
 * anyone to ask what happened.
 */
package com.zenalyst.housing.allocation;
