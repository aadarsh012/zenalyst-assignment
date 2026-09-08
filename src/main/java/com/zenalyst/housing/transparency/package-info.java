/**
 * The endpoints that make the result answerable rather than merely correct.
 *
 * <p>The brief says the final list will be questioned by an applicant, by a newspaper, and quite
 * possibly in court. There is one thing here for each of them, and between them they are the reason
 * every earlier phase was built the way it was.
 *
 * <ul>
 *   <li>{@link com.zenalyst.housing.transparency.ExplainService} — the applicant's answer to "why
 *       not me?". Identity, eligibility, proof of inclusion in the frozen register, lottery rank,
 *       standing in every pool competed in, and the cutoff each reached. Assembled from stored
 *       facts, never reconstructed, so two people asking the same question cannot get two
 *       answers.</li>
 *   <li>{@link com.zenalyst.housing.transparency.DrawVerificationService} — re-derives the draw
 *       from its published inputs and compares the result name by name.</li>
 *   <li>{@link com.zenalyst.housing.transparency.AuditChainVerifier} — rehashes the chain and names
 *       the first event that fails.</li>
 *   <li>{@link com.zenalyst.housing.transparency.ResultsExportService} — the file a newspaper
 *       downloads: every hash needed to check it in the header, no personal data in the rows.</li>
 * </ul>
 *
 * <p>None of this asks to be trusted. Every check performed here can be performed by somebody
 * outside, from published data, and the responses say how. A verification endpoint that only the
 * authority can run proves nothing; its value is that a discrepancy becomes visible to everyone at
 * once, including to the authority itself.
 */
package com.zenalyst.housing.transparency;
