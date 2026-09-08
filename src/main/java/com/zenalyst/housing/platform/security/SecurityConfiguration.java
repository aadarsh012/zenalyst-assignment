package com.zenalyst.housing.platform.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what.
 *
 * <h2>The principle</h2>
 *
 * <p>Everything that lets somebody <strong>influence</strong> the outcome is restricted. Everything
 * that lets somebody <strong>check</strong> it is open, and deliberately so: a verification endpoint
 * that requires the authority's own credentials proves nothing at all. The journalist rebuilding
 * the Merkle root, the applicant recomputing their own ticket, and the court re-deriving the whole
 * draw all work unauthenticated, by design.
 *
 * <h2>The one that matters most</h2>
 *
 * <p>The paper-import endpoint is the only route that can set a submission date in the past
 * (ADR-0005). Unsecured, any member of the public could post a backdated application and claim a
 * deadline they had missed. It was put on its own route in phase 1 precisely so that this
 * configuration could restrict it without the public route inheriting anything.
 *
 * <h2>Stateless</h2>
 *
 * <p>No sessions, no CSRF token, no cookies. Every request carries its own bearer token and is
 * authorised on its own terms. CSRF protection defends browser sessions against cross-site form
 * posts; with no session to ride on there is nothing for it to defend, and leaving it enabled would
 * only break the API clients this service exists for.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    private final String secret;

    public SecurityConfiguration(@Value("${housing.security.jwt.secret}") String secret) {
        this.secret = secret;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize

                        // --- open to everybody, on purpose -------------------------------------
                        // The endpoints by which this system is checked. Requiring credentials here
                        // would defeat the point of publishing anything.
                        .requestMatchers(HttpMethod.POST, "/api/v1/draws/*/verify").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/audit/verify",
                                "/api/v1/draws/*/results.csv",
                                "/api/v1/registry/**",
                                "/api/v1/schemes",
                                "/api/v1/schemes/*",
                                "/api/v1/schemes/*/draws",
                                "/api/v1/draws/*").permitAll()

                        // Applying, and objecting to a result. Both are things a member of the
                        // public does, and neither can influence an outcome on its own.
                        .requestMatchers(HttpMethod.POST, "/api/v1/schemes/*/applications").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/schemes/*/objections").permitAll()

                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/dev/**").permitAll()

                        // --- restricted --------------------------------------------------------
                        // The only route that may backdate a submission. See ADR-0005.
                        .requestMatchers(HttpMethod.POST, "/api/v1/schemes/*/applications:import")
                                .hasAnyRole(Role.OPERATOR.name(), Role.ADMIN.name())

                        // Recording facts about applicants: certificates, duplicate judgements.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/applications/*/verifications",
                                "/api/v1/schemes/*/verifications:import",
                                "/api/v1/schemes/*/deduplication:run",
                                "/api/v1/duplicate-reviews/*/decision")
                                .hasAnyRole(Role.OPERATOR.name(), Role.ADMIN.name())

                        // Deciding outcomes: the quota matrix, the freeze, the draw, objections.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/schemes/*/rules",
                                "/api/v1/schemes/*/rules/*:activate",
                                "/api/v1/schemes/*/registry:freeze",
                                "/api/v1/schemes/*/draws",
                                "/api/v1/draws/*/reveal",
                                "/api/v1/draws/*/execute",
                                "/api/v1/draws/*/publish",
                                "/api/v1/objections/*/decision")
                                .hasRole(Role.ADMIN.name())

                        // Everything else — including an applicant's own file, which is guarded per
                        // application number by @PreAuthorize rather than by role alone.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(authenticationConverter())));

        return http.build();
    }

    /**
     * Maps the {@code roles} claim onto Spring authorities.
     *
     * <p>The default converter reads {@code scope}/{@code scp} and prefixes with {@code SCOPE_}.
     * Roles read better in the rules above and in the tokens themselves, so they are named as such.
     */
    private JwtAuthenticationConverter authenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * HMAC-signed tokens.
     *
     * <p>A shared secret rather than a key pair because this service both mints and verifies its
     * own tokens; there is no third party that needs to verify one without being able to issue one.
     * A deployment fronted by a real identity provider would swap this for that provider's JWKS and
     * delete the minting side entirely.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key()).build();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key()));
    }

    private SecretKeySpec key() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
