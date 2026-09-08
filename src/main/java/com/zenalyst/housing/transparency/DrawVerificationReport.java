package com.zenalyst.housing.transparency;

import java.util.List;

/**
 * The result of re-deriving a draw from what was published.
 *
 * <p>Every check is reported, passed or failed, because a report that lists only failures cannot be
 * distinguished from a report that did not run.
 */
public record DrawVerificationReport(
        String drawId,
        boolean verified,
        String summary,
        List<Check> checks) {

    public DrawVerificationReport {
        checks = List.copyOf(checks);
    }

    /**
     * One thing that was checked.
     *
     * @param expected what the published record says
     * @param actual   what recomputing produced
     * @param detail   plain English, since this is read by people deciding whether to believe us
     */
    public record Check(String name, boolean passed, String expected, String actual, String detail) {

        static Check passed(String name, String value, String detail) {
            return new Check(name, true, value, value, detail);
        }

        static Check failed(String name, String expected, String actual, String detail) {
            return new Check(name, false, expected, actual, detail);
        }
    }
}
