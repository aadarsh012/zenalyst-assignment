package com.zenalyst.housing.platform.error;

import java.util.Map;

/**
 * An error the API deliberately returns, as opposed to one it suffers.
 *
 * <p>{@link #properties()} carries machine-readable context that gets merged into the
 * {@code ProblemDetail} body — the field that failed, the identifier that was not found.
 * Callers of this API include applicants and journalists, so "what exactly was wrong"
 * belongs in the response, not only in our logs.
 */
public class ApiException extends RuntimeException {

    private final transient ProblemType type;
    private final transient Map<String, Object> properties;

    public ApiException(ProblemType type, String detail) {
        this(type, detail, Map.of());
    }

    public ApiException(ProblemType type, String detail, Map<String, Object> properties) {
        super(detail);
        this.type = type;
        this.properties = Map.copyOf(properties);
    }

    public static ApiException notFound(String resource, String identifier) {
        return new ApiException(
                ProblemType.NOT_FOUND,
                "%s '%s' does not exist".formatted(resource, identifier),
                Map.of("resource", resource, "identifier", identifier));
    }

    public static ApiException conflict(String detail, Map<String, Object> properties) {
        return new ApiException(ProblemType.CONFLICT, detail, properties);
    }

    public ProblemType type() {
        return type;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
