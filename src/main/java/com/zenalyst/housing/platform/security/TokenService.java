package com.zenalyst.housing.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints bearer tokens.
 *
 * <p>Stands in for an identity provider, which is what a real deployment would use. It exists so
 * that this system can be run and demonstrated end to end without one, and the endpoint that
 * exposes it is confined to the development profile.
 *
 * <p>The subject is meaningful rather than decorative: for an applicant it is their application
 * number, and authorisation on the explain endpoint compares the two. That is the JWT principal
 * doing real work rather than merely proving somebody logged in.
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final Duration ttl;

    public TokenService(
            JwtEncoder encoder,
            Clock clock,
            @Value("${housing.security.jwt.issuer}") String issuer,
            @Value("${housing.security.jwt.ttl}") Duration ttl) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public String issue(String subject, List<Role> roles) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("roles", roles.stream().map(Enum::name).toList())
                .build();

        // The algorithm has to be stated. Nimbus defaults to RS256 and then cannot find a key,
        // because the key here is a shared secret rather than a certificate.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Duration ttl() {
        return ttl;
    }
}
