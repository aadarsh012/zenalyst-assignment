package com.zenalyst.housing.draw;

import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.error.ProblemType;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Takes the seed from a future round of a public randomness beacon.
 *
 * <p>At commit time the authority publishes a <em>round number</em> — say, the round due in ten
 * minutes. It cannot know what that round will contain, so it cannot generate candidate seeds and
 * pick a favourable one. At reveal time the round has happened and its value is public; the
 * authority reads it, and so can everybody else.
 *
 * <p>This is what {@link AuthorityCommittedSeedSource} cannot offer. A hash commitment proves the
 * seed did not change; a beacon round proves the seed was never the authority's to choose.
 *
 * <p>Verification needs nothing from this system. Fetch the round, take its randomness, and it is
 * the seed we used.
 */
@Component
public class DrandBeaconSeedSource implements SeedSource {

    private final DrandClient beacon;
    private final int leadRounds;

    public DrandBeaconSeedSource(
            DrandClient beacon,
            @Value("${housing.draw.beacon.lead-rounds}") int leadRounds) {
        this.beacon = beacon;
        this.leadRounds = leadRounds;
    }

    @Override
    public SeedSourceType type() {
        return SeedSourceType.DRAND_BEACON;
    }

    @Override
    public Commitment commit() {
        long target = beacon.latestRound() + leadRounds;
        // Nothing is kept back: the round number is the whole commitment, and it is public the
        // moment it is chosen.
        return new Commitment(null, target, null, null);
    }

    @Override
    public String reveal(Draw draw) {
        long round = draw.getBeaconRound();
        return beacon.randomnessAt(round)
                .orElseThrow(() -> new ApiException(ProblemType.CONFLICT,
                        "Beacon round %d has not been produced yet. The seed for this draw does not exist."
                                .formatted(round),
                        Map.of("beaconRound", round)));
    }
}
