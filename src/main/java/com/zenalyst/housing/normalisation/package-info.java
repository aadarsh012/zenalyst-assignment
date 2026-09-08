/**
 * Reduces what people typed to what can be compared.
 *
 * <p>Pure functions, no Spring, no database, no clock. Every class here is a static method over a
 * string, which is what makes the deduplication rules that depend on them testable in isolation
 * and identical everywhere they run.
 *
 * <p>The work is unglamorous and load-bearing. {@code 9876543210}, {@code 09876543210} and
 * {@code +91 98765 43210} are one phone number; {@code Ramesh Kumār} and {@code RAMESH KUMAR} are
 * one name; {@code 01/02/1990} and {@code 1990-02-01} are one date. If these disagree, two
 * applications from the same person never match and that person quietly gets two entries in the
 * draw.
 *
 * <p>Each normaliser reports failure as a {@link com.zenalyst.housing.normalisation.FieldViolation}
 * naming the field and a stable code, so that a form with six problems produces six messages
 * rather than one at a time.
 */
package com.zenalyst.housing.normalisation;
