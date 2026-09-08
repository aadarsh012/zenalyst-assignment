package com.zenalyst.housing.platform.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues a token to anybody who asks, for development and demonstration only.
 *
 * <p>{@code @Profile("dev")} is doing real work here: under any other profile this bean does not
 * exist and the route returns 404. There is no configuration flag to get wrong and no default that
 * quietly leaves it enabled — running with {@code SPRING_PROFILES_ACTIVE=prod} removes it from the
 * application entirely.
 *
 * <p>It stands in for an identity provider. A real deployment points the resource server at that
 * provider's JWKS and deletes both this class and {@link TokenService}.
 */
@RestController
@Profile("dev")
@Validated
public class DevTokenController {

    private final TokenService tokens;

    public DevTokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping(path = "/api/v1/dev/token",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TokenResponse issue(@RequestBody TokenRequest request) {
        return new TokenResponse(
                tokens.issue(request.subject(), request.roles()),
                "Bearer",
                tokens.ttl().toSeconds(),
                request.subject(),
                request.roles(),
                "Development only. This endpoint does not exist outside the dev profile.");
    }

    /**
     * @param subject who the token is for. For an applicant this must be their application number:
     *                the explain endpoint compares it against the application being requested.
     */
    public record TokenRequest(@NotBlank String subject, @NotEmpty List<Role> roles) {
    }

    public record TokenResponse(
            String token, String tokenType, long expiresInSeconds,
            String subject, List<Role> roles, String warning) {
    }
}
