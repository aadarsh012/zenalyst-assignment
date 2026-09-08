package com.zenalyst.housing.platform.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in production with the shipped development secret.
 *
 * <p>The failure this prevents is not exotic. A signing secret that has a working default gets
 * deployed unchanged more often than anyone likes to admit, and the symptom is nothing at all —
 * the system works perfectly, and anybody who has read the repository can mint an admin token and
 * run the draw.
 *
 * <p>A loud warning in development, and a refusal to start anywhere else. The same shape as the
 * guard on the identity pepper, for the same reason: a default that is convenient locally must be
 * impossible to carry into production silently.
 */
@Component
public class SecretGuard {

    private static final Logger log = LoggerFactory.getLogger(SecretGuard.class);

    static final String DEVELOPMENT_SECRET = "development-jwt-secret-not-for-production-use-32b";

    private final String secret;
    private final Environment environment;

    public SecretGuard(
            @Value("${housing.security.jwt.secret}") String secret, Environment environment) {
        this.secret = secret;
        this.environment = environment;
    }

    @PostConstruct
    void refuseDevelopmentSecretOutsideDevelopment() {
        if (!DEVELOPMENT_SECRET.equals(secret)) {
            return;
        }
        if (environment.matchesProfiles("dev", "test", "default")) {
            log.warn("Using the development JWT secret. Anybody who has read this repository can mint "
                    + "an administrator token. Set housing.security.jwt.secret (env HOUSING_JWT_SECRET) "
                    + "before any real use.");
            return;
        }
        throw new IllegalStateException(
                "housing.security.jwt.secret is still the development default under profile(s) "
                        + String.join(",", environment.getActiveProfiles())
                        + ". Refusing to start: anybody could mint an administrator token.");
    }
}
