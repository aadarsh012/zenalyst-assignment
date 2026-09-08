package com.zenalyst.housing.rules;

import java.time.Instant;

/**
 * A rule version as published.
 *
 * <p>{@code rulesDocument} is the canonical text that was hashed, returned verbatim so that anyone
 * checking {@code rulesHash} hashes the same bytes we did.
 */
public record RuleVersionResponse(
        String schemeCode,
        String version,
        RuleVersion.Status status,
        String rulesHash,
        String rulesDocument,
        Instant createdAt,
        String createdBy,
        Instant activatedAt,
        String activatedBy) {

    static RuleVersionResponse of(String schemeCode, RuleVersion version) {
        return new RuleVersionResponse(
                schemeCode, version.getVersion(), version.getStatus(), version.getRulesHash(),
                version.getRulesDocument(), version.getCreatedAt(), version.getCreatedBy(),
                version.getActivatedAt(), version.getActivatedBy());
    }
}
