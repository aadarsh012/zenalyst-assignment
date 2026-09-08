package com.zenalyst.housing.draw;

import com.zenalyst.housing.platform.hash.Hashing;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * The authority generates the seed, publishes a hash of it, and reveals it after the fact.
 *
 * <p>The salt matters more than it looks. Without it the commitment is
 * {@code SHA-256(seed)}, and anyone who can guess the seed space can confirm a guess offline. With
 * 128 bits of salt there is nothing to guess.
 *
 * <p><strong>What this proves, and what it does not.</strong> It proves the seed was fixed before
 * it was revealed, so the authority cannot have adjusted it once the result was visible. It does
 * <em>not</em> prove the seed was not chosen: nothing here stops an authority generating a thousand
 * seeds, running the draw against each against the already-frozen register, and publishing the
 * commitment for whichever produced the list it preferred. Every published artefact would still
 * verify.
 *
 * <p>That gap is not closeable by any amount of care on this side of the wire, which is why
 * {@link DrandBeaconSeedSource} exists. This implementation remains the default because it works
 * without depending on a third party being reachable, and ADR-0011 states the trade rather than
 * leaving it to be discovered.
 */
@Component
public class AuthorityCommittedSeedSource implements SeedSource {

    private static final int SEED_BYTES = 32;
    private static final int SALT_BYTES = 16;

    private final SecureRandom random = new SecureRandom();

    @Override
    public SeedSourceType type() {
        return SeedSourceType.AUTHORITY_COMMITTED;
    }

    @Override
    public Commitment commit() {
        String seed = randomHex(SEED_BYTES);
        String salt = randomHex(SALT_BYTES);
        return new Commitment(commitmentOf(seed, salt), null, seed, salt);
    }

    @Override
    public String reveal(Draw draw) {
        return draw.getSeed();
    }

    /**
     * The commitment formula, published so it can be checked.
     *
     * <p>{@code SHA-256(seed || ":" || salt)}. The separator is there because {@code seed} and
     * {@code salt} are fixed-length hexadecimal and could otherwise be re-split at a different
     * point to produce the same input.
     */
    public static String commitmentOf(String seed, String salt) {
        return Hashing.sha256Hex(seed + ":" + salt);
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Hashing.toHex(buffer);
    }
}
