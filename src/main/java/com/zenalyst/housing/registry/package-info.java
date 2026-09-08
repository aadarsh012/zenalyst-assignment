/**
 * Freezes the inputs to a draw, and proves afterwards what they were.
 *
 * <h2>Where to start</h2>
 *
 * <p>{@link com.zenalyst.housing.registry.RegistryFreezeService} for the snapshot,
 * {@link com.zenalyst.housing.registry.MerkleTree} for the cryptography.
 *
 * <h2>What freezing buys</h2>
 *
 * <p>Without it, "the draw was run on the register" means the register as it happened to be at
 * some unrecorded moment. With it, the draw runs against a specific set of rows whose root has
 * already been published — so an accusation that somebody was added, removed or reclassified after
 * the fact is answerable by arithmetic rather than by assurance.
 *
 * <p>The root is published <em>before</em> any seed exists. An authority that could still change
 * the candidate list after seeing the seed could choose the outcome.
 *
 * <p>Candidates are sorted by application number, rendered to canonical JSON, and hashed into a
 * Merkle tree. Freezing unchanged data twice produces an identical root — that reproducibility is
 * the property being relied on, not a happy accident.
 *
 * <p>The tree also yields per-applicant <strong>inclusion proofs</strong>: a handful of hashes with
 * which one applicant can verify their row was among the inputs, without downloading the register
 * and without trusting this service.
 */
package com.zenalyst.housing.registry;
