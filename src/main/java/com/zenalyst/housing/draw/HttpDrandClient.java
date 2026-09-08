package com.zenalyst.housing.draw;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * The League of Entropy's public HTTP beacon.
 *
 * <p>Rounds are produced on a fixed schedule and are readable by anybody at
 * {@code /public/{round}}. That is the point: an applicant, a journalist or a court can fetch the
 * same round this system used and confirm the seed independently, with no cooperation from the
 * authority.
 */
@Component
public class HttpDrandClient implements DrandClient {

    private final RestTemplate http;
    private final String baseUrl;

    public HttpDrandClient(
            RestTemplateBuilder builder,
            @Value("${housing.draw.beacon.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public long latestRound() {
        JsonNode response = http.getForObject(baseUrl + "/public/latest", JsonNode.class);
        if (response == null || !response.hasNonNull("round")) {
            throw new IllegalStateException("beacon returned no round at " + baseUrl);
        }
        return response.path("round").asLong();
    }

    @Override
    public Optional<String> randomnessAt(long round) {
        try {
            JsonNode response = http.getForObject(baseUrl + "/public/" + round, JsonNode.class);
            return response == null || !response.hasNonNull("randomness")
                    ? Optional.empty()
                    : Optional.of(response.path("randomness").asText());
        } catch (HttpClientErrorException e) {
            // The beacon answers 404 for a round it has not reached. That is the expected answer to
            // "has the future happened yet", not an error.
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
