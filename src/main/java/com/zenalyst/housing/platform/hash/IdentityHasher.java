package com.zenalyst.housing.platform.hash;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Turns a national identity number into a stable, scheme-scoped token.
 *
 * <p>Deduplication needs to know that two applications carry the same identity number. It does
 * not need to know the number. So the number is never stored: what goes into the database is
 * {@code HMAC-SHA256(pepper, schemeCode ‖ id)}, which answers "same person?" and nothing else.
 * A housing authority has no business holding a searchable table of four thousand national
 * identity numbers, and cannot leak what it never kept.
 *
 * <p>Scoping by scheme code means the same person applying to two different schemes produces two
 * unrelated tokens, so the tokens cannot be used to build a cross-scheme profile of an
 * individual.
 *
 * <p><strong>What this does not protect against.</strong> The identity number space is only
 * 10<sup>12</sup> wide. An attacker holding both the database and the pepper can enumerate it
 * and recover every number; the pepper is the whole of the secret. It is therefore kept out of
 * the database and supplied as configuration, so that a database compromise alone is not
 * sufficient. A production deployment handling real identity numbers should hold the key in an
 * HSM or KMS and never let the application process see it — see ADR-0003.
 */
@Component
public class IdentityHasher {

    private static final Logger log = LoggerFactory.getLogger(IdentityHasher.class);

    /** The value shipped for local development. Recognisable on sight, and refused in production. */
    static final String DEVELOPMENT_PEPPER = "development-pepper-not-for-production";

    private final String pepper;
    private final Environment environment;

    public IdentityHasher(@Value("${housing.identity.pepper}") String pepper, Environment environment) {
        this.pepper = pepper;
        this.environment = environment;
    }

    @PostConstruct
    void refuseDevelopmentPepperOutsideDevelopment() {
        if (!DEVELOPMENT_PEPPER.equals(pepper)) {
            return;
        }
        boolean developmentProfile = environment.matchesProfiles("dev", "test", "default");
        if (developmentProfile) {
            log.warn("Using the development identity pepper. Identity tokens are NOT confidential. "
                    + "Set housing.identity.pepper (env HOUSING_IDENTITY_PEPPER) before any real use.");
        } else {
            throw new IllegalStateException(
                    "housing.identity.pepper is still the development default under profile(s) "
                            + String.join(",", environment.getActiveProfiles())
                            + ". Refusing to start: identity tokens would be trivially reversible.");
        }
    }

    /**
     * @param schemeCode          scopes the token so it cannot be correlated across schemes
     * @param normalisedIdentityId the twelve digits, already validated
     */
    public String token(String schemeCode, String normalisedIdentityId) {
        return Hashing.hmacSha256Hex(pepper, schemeCode + ":" + normalisedIdentityId);
    }
}
