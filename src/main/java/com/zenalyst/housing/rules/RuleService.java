package com.zenalyst.housing.rules;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.allocation.AllocationRules;
import com.zenalyst.housing.allocation.SeatPool;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.error.ProblemType;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and activates quota matrices.
 *
 * <p>Rules are validated when they are written, not when they are used. A matrix whose horizontal
 * reservations exceed the pool cannot be satisfied, and discovering that mid-draw — with the seed
 * already public and the result being watched — is not a position anyone should be put in.
 */
@Service
public class RuleService {

    private final SchemeRepository schemes;
    private final RuleVersionRepository ruleVersions;
    private final AuditWriter audit;
    private final Clock clock;

    public RuleService(
            SchemeRepository schemes, RuleVersionRepository ruleVersions,
            AuditWriter audit, Clock clock) {
        this.schemes = schemes;
        this.ruleVersions = ruleVersions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RuleVersionResponse create(String schemeCode, CreateRuleVersionRequest request, String createdBy) {
        Scheme scheme = scheme(schemeCode);

        ruleVersions.findBySchemeIdAndVersion(scheme.getId(), request.version())
                .ifPresent(existing -> {
                    throw ApiException.conflict(
                            "Rule version '%s' already exists for %s.".formatted(request.version(), schemeCode),
                            Map.of("schemeCode", schemeCode, "version", request.version()));
                });

        AllocationRules rules = toAllocationRules(request);
        requireSeatsMatchScheme(scheme, rules);

        RuleVersion created = ruleVersions.saveAndFlush(RuleVersion.draft(
                scheme.getId(), request.version(), rules.hash(), rules.document(),
                clock.instant(), createdBy));

        audit.append(createdBy, AuditAction.RULE_VERSION_CREATED, "scheme", schemeCode,
                payload(schemeCode, created));

        return RuleVersionResponse.of(schemeCode, created);
    }

    /**
     * Puts a version in force, superseding whatever it replaces.
     *
     * <p>Both changes happen in one transaction. A moment in which a scheme has two active rule
     * versions, or none, is a moment in which "which rules are in force?" has no answer.
     */
    @Transactional
    public RuleVersionResponse activate(String schemeCode, String version, String activatedBy) {
        Scheme scheme = scheme(schemeCode);
        RuleVersion target = ruleVersions.findBySchemeIdAndVersion(scheme.getId(), version)
                .orElseThrow(() -> ApiException.notFound("rule version", version));

        if (target.getStatus() != RuleVersion.Status.DRAFT) {
            throw ApiException.conflict(
                    "Rule version '%s' is %s.".formatted(version, target.getStatus()),
                    Map.of("version", version, "status", target.getStatus().name()));
        }

        ruleVersions.findBySchemeIdAndStatus(scheme.getId(), RuleVersion.Status.ACTIVE)
                .ifPresent(current -> {
                    current.supersede();
                    ruleVersions.saveAndFlush(current);
                });

        Instant now = clock.instant();
        target.activate(now, activatedBy);
        ruleVersions.saveAndFlush(target);

        audit.append(activatedBy, AuditAction.RULE_VERSION_ACTIVATED, "scheme", schemeCode,
                payload(schemeCode, target));

        return RuleVersionResponse.of(schemeCode, target);
    }

    @Transactional(readOnly = true)
    public RuleVersion activeVersion(Scheme scheme) {
        return ruleVersions.findBySchemeIdAndStatus(scheme.getId(), RuleVersion.Status.ACTIVE)
                .orElseThrow(() -> new ApiException(ProblemType.CONFLICT,
                        "Scheme '%s' has no active rule version. Create one and activate it before drawing."
                                .formatted(scheme.getCode()),
                        Map.of("schemeCode", scheme.getCode())));
    }

    @Transactional(readOnly = true)
    public List<RuleVersionResponse> list(String schemeCode) {
        Scheme scheme = scheme(schemeCode);
        return ruleVersions.findBySchemeIdOrderByCreatedAtDesc(scheme.getId()).stream()
                .map(version -> RuleVersionResponse.of(schemeCode, version))
                .toList();
    }

    /** Rebuilds the allocator's rules from a stored version's canonical document. */
    public AllocationRules toAllocationRules(RuleVersion version) {
        return RuleDocuments.parse(version.getRulesDocument());
    }

    static AllocationRules toAllocationRules(CreateRuleVersionRequest request) {
        Map<SeatPool, Integer> seats = new EnumMap<>(SeatPool.class);
        seats.putAll(request.seats());

        List<AllocationRules.HorizontalReservation> horizontal = request.horizontalReservations().stream()
                .map(entry -> new AllocationRules.HorizontalReservation(entry.category(), entry.share()))
                .toList();

        try {
            return new AllocationRules(seats, horizontal);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, e.getMessage());
        }
    }

    /**
     * The quota matrix must add up to the scheme's flats.
     *
     * <p>A matrix totalling 599 or 601 is a typo, and one that reaches a draw allots the wrong
     * number of flats to real people. Refusing it at creation costs a moment; refusing it later
     * costs a re-draw.
     */
    private void requireSeatsMatchScheme(Scheme scheme, AllocationRules rules) {
        if (rules.totalSeats() != scheme.getTotalFlats()) {
            throw new ApiException(ProblemType.VALIDATION_FAILED,
                    "The quota matrix totals %d seats but scheme '%s' has %d flats."
                            .formatted(rules.totalSeats(), scheme.getCode(), scheme.getTotalFlats()),
                    Map.of("quotaTotal", rules.totalSeats(), "schemeFlats", scheme.getTotalFlats()));
        }
    }

    private ObjectNode payload(String schemeCode, RuleVersion version) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("rulesHash", version.getRulesHash());
        payload.put("schemeCode", schemeCode);
        payload.put("status", version.getStatus().name());
        payload.put("version", version.getVersion());
        return payload;
    }

    private Scheme scheme(String schemeCode) {
        return schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));
    }
}
