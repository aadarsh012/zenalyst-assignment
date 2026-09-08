package com.zenalyst.housing.draw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.hash.Hashing;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SeedSourceTest {

    @Nested
    @DisplayName("authority-committed seeds")
    class AuthorityCommitted {

        private final AuthorityCommittedSeedSource source = new AuthorityCommittedSeedSource();

        @Test
        @DisplayName("the commitment is a hash of the seed and salt, and can be checked by anyone")
        void commitmentIsVerifiable() {
            SeedSource.Commitment commitment = source.commit();

            assertThat(commitment.commitmentHash())
                    .isEqualTo(Hashing.sha256Hex(commitment.seed() + ":" + commitment.salt()))
                    .matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("every commitment uses a fresh seed and a fresh salt")
        void seedsAndSaltsAreNeverReused() {
            Set<String> seeds = new HashSet<>();
            Set<String> salts = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                SeedSource.Commitment commitment = source.commit();
                seeds.add(commitment.seed());
                salts.add(commitment.salt());
            }
            assertThat(seeds).hasSize(200);
            assertThat(salts).hasSize(200);
        }

        @Test
        @DisplayName("the salt is what stops the commitment being brute-forced")
        void saltMakesTheCommitmentUnguessable() {
            SeedSource.Commitment commitment = source.commit();

            // Without a salt the commitment is SHA-256(seed), and anyone who can enumerate the seed
            // space can confirm a guess offline before the reveal.
            assertThat(commitment.commitmentHash()).isNotEqualTo(Hashing.sha256Hex(commitment.seed()));
            assertThat(commitment.salt()).hasSize(32);
            assertThat(commitment.seed()).hasSize(64);
        }

        @Test
        @DisplayName("the separator stops a seed and salt being re-split at a different point")
        void separatorPreventsAmbiguousConcatenation() {
            // "ab" + "cd" and "a" + "bcd" must not commit to the same thing.
            assertThat(AuthorityCommittedSeedSource.commitmentOf("ab", "cd"))
                    .isNotEqualTo(AuthorityCommittedSeedSource.commitmentOf("a", "bcd"));
        }
    }

    @Nested
    @DisplayName("beacon seeds")
    class Beacon {

        /** A beacon that has reached round 1000 and knows nothing beyond it. */
        private final DrandClient beacon = new DrandClient() {
            @Override
            public long latestRound() {
                return 1000;
            }

            @Override
            public Optional<String> randomnessAt(long round) {
                return round <= 1000 ? Optional.of("beacon-randomness-%d".formatted(round)) : Optional.empty();
            }
        };

        @Test
        @DisplayName("the commitment is a round that has not happened yet")
        void commitsToAFutureRound() {
            SeedSource.Commitment commitment = new DrandBeaconSeedSource(beacon, 20).commit();

            // The authority cannot know this round's value, so it cannot generate candidate seeds
            // and publish the commitment for whichever produced the list it wanted.
            assertThat(commitment.beaconRound()).isEqualTo(1020);
            assertThat(commitment.seed()).isNull();
            assertThat(commitment.commitmentHash()).isNull();
        }

        @Test
        @DisplayName("a round that has not been produced cannot be revealed")
        void refusesToRevealAFutureRound() {
            DrandBeaconSeedSource source = new DrandBeaconSeedSource(beacon, 20);
            Draw draw = drawCommittedTo(source);

            // Inventing a value here would defeat the entire mechanism, so it refuses instead.
            assertThatThrownBy(() -> source.reveal(draw))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("has not been produced yet");
        }

        @Test
        @DisplayName("once the round exists, its randomness is the seed")
        void revealsTheRoundsRandomness() {
            // Committing zero rounds ahead means the round already exists — only valid in a test.
            DrandBeaconSeedSource source = new DrandBeaconSeedSource(beacon, 0);
            Draw draw = drawCommittedTo(source);

            assertThat(source.reveal(draw)).isEqualTo("beacon-randomness-1000");
        }

        private Draw drawCommittedTo(DrandBeaconSeedSource source) {
            return Draw.commit(
                    java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "root",
                    java.util.UUID.randomUUID(), "rules", SeedSourceType.DRAND_BEACON,
                    source.commit(), java.time.Instant.parse("2026-09-08T10:00:00Z"), "registrar");
        }
    }
}
