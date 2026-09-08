/**
 * Running the draw.
 *
 * <p>At present this package holds only the rehearsal:
 * {@link com.zenalyst.housing.draw.DryRunService} runs the allocator against a frozen register and
 * an active quota matrix, and keeps none of it. It exists so that an unfillable reservation or a
 * quota matrix one flat short is found before a seed is committed to, rather than after — because
 * re-drawing a public lottery is something an authority does at most once.
 *
 * <p>Phase 5 adds the ceremony around it: committing to a seed before revealing it, executing the
 * draw as a resumable job, and persisting the result.
 */
package com.zenalyst.housing.draw;
