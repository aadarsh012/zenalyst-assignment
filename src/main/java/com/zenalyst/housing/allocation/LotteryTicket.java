package com.zenalyst.housing.allocation;

import com.zenalyst.housing.platform.hash.Hashing;

/**
 * Turns a seed and an application number into that applicant's position in the draw.
 *
 * <pre>
 *   ticket = HMAC-SHA256(key = seed, message = applicationNo)
 *   order  = ticket ascending as hexadecimal, ties broken by application number
 * </pre>
 *
 * <h2>Why not {@code Collections.shuffle(list, new Random(seed))}</h2>
 *
 * <p>Because it would not be verifiable, which is the only thing that matters here.
 *
 * <p>A seeded shuffle produces an order that depends on the order of the input list, so anyone
 * re-running it would first have to reproduce our list ordering exactly. It also depends on the
 * JDK's {@code Random} implementation, which is a Java-specific linear congruential generator — a
 * journalist reimplementing the draw in Python would get entirely different names and reasonably
 * conclude the authority had cheated.
 *
 * <p>An HMAC over the application number has none of those properties. Each ticket depends on
 * nothing but the seed and that one applicant's number, so it can be computed independently, in
 * any language, in any order, for any single applicant without reference to the rest. Sorting by
 * ticket is then just sorting.
 *
 * <p>The tie-break on application number is for completeness rather than expectation: two distinct
 * inputs colliding on a 256-bit HMAC is not something that will happen, but an ordering with an
 * undefined case is not an ordering.
 */
public final class LotteryTicket {

    private LotteryTicket() {
    }

    public static String forApplication(String seed, String applicationNo) {
        return Hashing.hmacSha256Hex(seed, applicationNo);
    }
}
