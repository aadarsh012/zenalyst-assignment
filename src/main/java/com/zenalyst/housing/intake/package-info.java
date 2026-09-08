/**
 * Accepts applications into the register, from both channels.
 *
 * <h2>Where to start</h2>
 *
 * <p>{@link com.zenalyst.housing.intake.IntakeService} for online submission,
 * {@link com.zenalyst.housing.intake.PaperImportService} for forms typed up after the fact.
 *
 * <h2>Why two entry points and not one</h2>
 *
 * <p>Only the paper path may record a submission date in the past, because a clerk typing up a
 * form received last week has to. If the two shared an endpoint, any member of the public could
 * post a backdated application and claim a deadline they had missed. Keeping the capability on its
 * own route means it can be secured with a role on one route, with no possibility of the public
 * one inheriting the privilege.
 *
 * <h2>What every accepted application carries</h2>
 *
 * <p>Its submission exactly as received ({@code rawPayload}), every field in normalised form for
 * later matching, and two distinct timestamps — when the applicant applied, and when the system
 * recorded it. Deadlines use the first. See ADR-0005.
 *
 * <p>The national identity number is validated on arrival and then discarded; only a scheme-scoped
 * token survives. See ADR-0003.
 */
package com.zenalyst.housing.intake;
