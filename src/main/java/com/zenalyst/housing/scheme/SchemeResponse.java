package com.zenalyst.housing.scheme;

import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of a scheme. Separate from the entity on purpose: what we persist
 * and what we publish are different contracts, and they will diverge.
 */
public record SchemeResponse(
        UUID id,
        String code,
        String name,
        int totalFlats,
        Instant applicationsOpenAt,
        Instant applicationsCloseAt,
        String status) {

    public static SchemeResponse from(Scheme scheme) {
        return new SchemeResponse(
                scheme.getId(),
                scheme.getCode(),
                scheme.getName(),
                scheme.getTotalFlats(),
                scheme.getApplicationsOpenAt(),
                scheme.getApplicationsCloseAt(),
                scheme.getStatus());
    }
}
