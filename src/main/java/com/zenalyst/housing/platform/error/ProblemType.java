package com.zenalyst.housing.platform.error;

import org.springframework.http.HttpStatus;

/**
 * The catalogue of error conditions this API can return.
 *
 * <p>Each constant is a stable, documented contract: the {@code type} URI is what an
 * integrator branches on, and it must not change once published. Human-readable titles and
 * detail messages may be reworded freely; the URI may not.
 */
public enum ProblemType {

    VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST, "Request failed validation"),
    MALFORMED_REQUEST("malformed-request", HttpStatus.BAD_REQUEST, "Request could not be parsed"),
    NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Resource not found"),
    UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Not permitted"),
    CONFLICT("conflict", HttpStatus.CONFLICT, "Request conflicts with current state"),
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private static final String BASE = "https://zenalyst.example/problems/";

    private final String slug;
    private final HttpStatus status;
    private final String title;

    ProblemType(String slug, HttpStatus status, String title) {
        this.slug = slug;
        this.status = status;
        this.title = title;
    }

    public String typeUri() {
        return BASE + slug;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
