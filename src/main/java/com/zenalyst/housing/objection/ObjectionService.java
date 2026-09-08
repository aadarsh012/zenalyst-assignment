package com.zenalyst.housing.objection;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.draw.Draw;
import com.zenalyst.housing.draw.DrawRepository;
import com.zenalyst.housing.draw.DrawStatus;
import com.zenalyst.housing.intake.ApplicationRepository;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Files and adjudicates challenges to a published result.
 *
 * <p>The governing rule is that upholding an objection <em>changes nothing by itself</em>. It
 * records a finding, states a remedy, and authorises a superseding draw. The correction to the
 * underlying record — verifying a certificate that was wrongly refused, rejecting a duplicate link
 * that joined two different people — happens through the ordinary endpoints, each leaving its own
 * audit trail, and the new draw is a separate deliberate act after that.
 *
 * <p>Splitting it that way is not bureaucracy for its own sake. An "uphold" button that silently
 * corrected records and reissued a list would be a single call that rewrote a public lottery, and
 * the only evidence of what it had done would be whatever it chose to log.
 */
@Service
public class ObjectionService {

    private final SchemeRepository schemes;
    private final DrawRepository draws;
    private final ApplicationRepository applications;
    private final ObjectionRepository objections;
    private final AuditWriter audit;
    private final Clock clock;

    public ObjectionService(
            SchemeRepository schemes,
            DrawRepository draws,
            ApplicationRepository applications,
            ObjectionRepository objections,
            AuditWriter audit,
            Clock clock) {
        this.schemes = schemes;
        this.draws = draws;
        this.applications = applications;
        this.objections = objections;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ObjectionResponse file(String schemeCode, FileObjectionRequest request, String filedBy) {
        Scheme scheme = scheme(schemeCode);

        if (request.applicationNo() != null
                && applications.findByApplicationNo(request.applicationNo()).isEmpty()) {
            throw ApiException.notFound("application", request.applicationNo());
        }

        // Attached to the scheme's current published draw, if there is one. An objection filed
        // before any draw is still valid — it is simply about the register rather than the result.
        UUID drawId = publishedDrawOf(scheme).map(Draw::getId).orElse(null);

        Objection objection = objections.saveAndFlush(Objection.file(
                scheme.getId(), drawId, request.applicationNo(), request.ground(),
                request.statement(), request.supportingReference(), filedBy, clock.instant()));

        audit.append(filedBy, AuditAction.OBJECTION_FILED, "objection", objection.getId().toString(),
                filePayload(schemeCode, objection));

        return ObjectionResponse.of(objection, schemeCode);
    }

    @Transactional
    public ObjectionResponse decide(UUID objectionId, ObjectionDecisionRequest request, String decidedBy) {
        Objection objection = objections.findById(objectionId)
                .orElseThrow(() -> ApiException.notFound("objection", objectionId.toString()));

        if (objection.getStatus() != ObjectionStatus.OPEN) {
            throw ApiException.conflict(
                    "Objection %s was already %s by %s."
                            .formatted(objectionId, objection.getStatus(), objection.getDecidedBy()),
                    Map.of("objectionId", objectionId.toString(),
                            "status", objection.getStatus().name()));
        }
        if (request.outcome() == ObjectionStatus.UPHELD
                && (request.remedy() == null || request.remedy().isBlank())) {
            throw new ApiException(com.zenalyst.housing.platform.error.ProblemType.VALIDATION_FAILED,
                    "An upheld objection must state its remedy: what has to be corrected, and whether "
                            + "a superseding draw is required.");
        }

        Instant now = clock.instant();
        objection.decide(request.outcome(), request.reason(), request.remedy(), decidedBy, now);
        objections.saveAndFlush(objection);

        audit.append(decidedBy, AuditAction.OBJECTION_DECIDED, "objection", objectionId.toString(),
                decisionPayload(objection));

        return ObjectionResponse.of(objection, schemeCodeOf(objection.getSchemeId()));
    }

    @Transactional(readOnly = true)
    public List<ObjectionResponse> list(String schemeCode, ObjectionStatus status) {
        Scheme scheme = scheme(schemeCode);
        List<Objection> found = status == null
                ? objections.findBySchemeIdOrderByFiledAtDesc(scheme.getId())
                : objections.findBySchemeIdAndStatusOrderByFiledAtDesc(scheme.getId(), status);
        return found.stream().map(objection -> ObjectionResponse.of(objection, schemeCode)).toList();
    }

    @Transactional(readOnly = true)
    public ObjectionResponse get(UUID objectionId) {
        Objection objection = objections.findById(objectionId)
                .orElseThrow(() -> ApiException.notFound("objection", objectionId.toString()));
        return ObjectionResponse.of(objection, schemeCodeOf(objection.getSchemeId()));
    }

    private java.util.Optional<Draw> publishedDrawOf(Scheme scheme) {
        return draws.findBySchemeIdOrderByCommittedAtDesc(scheme.getId()).stream()
                .filter(draw -> draw.getStatus() == DrawStatus.PUBLISHED)
                .findFirst();
    }

    private ObjectNode filePayload(String schemeCode, Objection objection) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        if (objection.getApplicationNo() != null) {
            payload.put("applicationNo", objection.getApplicationNo());
        }
        if (objection.getDrawId() != null) {
            payload.put("drawId", objection.getDrawId().toString());
        }
        payload.put("ground", objection.getGround().name());
        payload.put("objectionId", objection.getId().toString());
        payload.put("schemeCode", schemeCode);
        return payload;
    }

    private ObjectNode decisionPayload(Objection objection) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("objectionId", objection.getId().toString());
        payload.put("outcome", objection.getStatus().name());
        payload.put("reason", objection.getDecisionReason());
        if (objection.getRemedy() != null) {
            payload.put("remedy", objection.getRemedy());
        }
        return payload;
    }

    private Scheme scheme(String schemeCode) {
        return schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));
    }

    private String schemeCodeOf(UUID schemeId) {
        return schemes.findById(schemeId).map(Scheme::getCode).orElse("unknown");
    }
}
