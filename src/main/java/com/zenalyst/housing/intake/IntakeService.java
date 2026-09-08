package com.zenalyst.housing.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.idempotency.IdempotencyService;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts applications, from either channel, into the register.
 *
 * <p>Everything an accepted application implies happens in one transaction: the row, the audit
 * event that records having accepted it, and the idempotency entry that stops the same
 * submission being accepted twice. There is no ordering of those three in which a crash leaves a
 * defensible state, so they are not ordered — they commit together.
 */
@Service
public class IntakeService {

    static final String ONLINE_ENDPOINT = "POST /api/v1/schemes/{code}/applications";

    private final SchemeRepository schemes;
    private final ApplicationRepository applications;
    private final ApplicationNormaliser normaliser;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IntakeService(
            SchemeRepository schemes,
            ApplicationRepository applications,
            ApplicationNormaliser normaliser,
            IdempotencyService idempotency,
            AuditWriter audit,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Clock clock) {
        this.schemes = schemes;
        this.applications = applications;
        this.normaliser = normaliser;
        this.idempotency = idempotency;
        this.audit = audit;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param idempotencyKey may be null; when supplied, a repeat of the same request returns the
     *                       original response instead of creating a second application
     */
    @Transactional
    public IntakeOutcome submitOnline(
            String schemeCode, SubmitApplicationRequest request, String idempotencyKey) {

        String requestHash = idempotencyKey == null
                ? null
                : IdempotencyService.hashRequest(canonicalise(request));

        if (idempotencyKey != null) {
            Optional<com.zenalyst.housing.platform.idempotency.StoredResponse> replayed =
                    idempotency.replay(idempotencyKey, ONLINE_ENDPOINT, requestHash);
            if (replayed.isPresent()) {
                return new IntakeOutcome(replayed.get().status(), replayed.get().body(), true);
            }
        }

        Scheme scheme = openScheme(schemeCode);
        Instant now = clock.instant();

        // For an online application the moment of submission is the moment of receipt: there is
        // no interval during which the form sat in a tray.
        NormalisedApplication normalised = normaliser.normalise(
                scheme.getCode(), ApplicationChannel.ONLINE, request, now,
                ApplicationNormaliser.toLocalDate(now), null, null);

        requireWithinWindow(scheme, normalised.submittedAt());

        Application application = applications.save(
                Application.of(scheme.getId(), nextApplicationNo(scheme), normalised, now));

        audit.append("public", AuditAction.APPLICATION_RECEIVED, "application",
                application.getApplicationNo(), auditPayload(application, scheme));

        String body = serialise(ApplicationResponse.from(application, scheme.getCode()));
        if (idempotencyKey != null) {
            idempotency.record(idempotencyKey, ONLINE_ENDPOINT, requestHash, 201, body);
        }
        return new IntakeOutcome(201, body, false);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse byApplicationNo(String applicationNo) {
        Application application = applications.findByApplicationNo(applicationNo)
                .orElseThrow(() -> ApiException.notFound("application", applicationNo));
        String schemeCode = schemes.findById(application.getSchemeId())
                .map(Scheme::getCode)
                .orElseThrow(() -> ApiException.notFound("scheme", application.getSchemeId().toString()));
        return ApplicationResponse.from(application, schemeCode);
    }

    /**
     * Application numbers are {@code SCHEME-000001}. Sequential and human-quotable: an applicant
     * reads it over the phone, and a clerk finds it. The sequence reveals arrival order, which is
     * harmless — the draw is decided by a seeded hash of the application number, not by when it
     * arrived.
     */
    private String nextApplicationNo(Scheme scheme) {
        Long next = jdbc.queryForObject("SELECT nextval('application_no_seq')", Long.class);
        return "%s-%06d".formatted(scheme.getCode(), next);
    }

    Scheme openScheme(String schemeCode) {
        Scheme scheme = schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));
        if (!"OPEN".equals(scheme.getStatus())) {
            throw ApiException.conflict(
                    "Scheme '%s' is not accepting applications (status %s)."
                            .formatted(schemeCode, scheme.getStatus()),
                    Map.of("schemeCode", schemeCode, "status", scheme.getStatus()));
        }
        return scheme;
    }

    /**
     * The deadline is tested against when the applicant applied, never against when the record
     * was created. For paper applications those are different instants, and using the wrong one
     * would disqualify people whose forms a clerk had not yet reached.
     */
    void requireWithinWindow(Scheme scheme, Instant submittedAt) {
        if (submittedAt.isBefore(scheme.getApplicationsOpenAt())) {
            throw ApiException.conflict(
                    "Applications for '%s' had not opened at %s.".formatted(scheme.getCode(), submittedAt),
                    Map.of("submittedAt", submittedAt.toString(),
                            "opensAt", scheme.getApplicationsOpenAt().toString()));
        }
        if (submittedAt.isAfter(scheme.getApplicationsCloseAt())) {
            throw ApiException.conflict(
                    "Applications for '%s' had closed at %s.".formatted(scheme.getCode(), submittedAt),
                    Map.of("submittedAt", submittedAt.toString(),
                            "closedAt", scheme.getApplicationsCloseAt().toString()));
        }
    }

    /**
     * What the audit chain records about an accepted application.
     *
     * <p>Notably absent: the applicant's name, address, phone and email. The chain is the part of
     * this system most likely to be exported, quoted in a report or handed to a court, and it
     * needs to establish <em>that</em> an application was accepted and with what
     * allocation-relevant attributes — not to become a second copy of the applicant register.
     * The application number leads to the full record for anyone entitled to see it.
     */
    ObjectNode auditPayload(Application application, Scheme scheme) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("applicationNo", application.getApplicationNo());
        payload.put("category", application.getCategory().name());
        payload.put("channel", application.getChannel().name());
        payload.put("disability", application.hasDisability());
        payload.put("exServiceperson", application.isExServiceperson());
        payload.put("gender", application.getGender().name());
        payload.put("localResident", application.isLocalResident());
        payload.put("schemeCode", scheme.getCode());
        payload.put("submittedAt", application.getSubmittedAt().toString());
        return payload;
    }

    private String canonicalise(SubmitApplicationRequest request) {
        try {
            return com.zenalyst.housing.platform.hash.CanonicalJson.render(
                    objectMapper.valueToTree(request));
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    com.zenalyst.housing.platform.error.ProblemType.MALFORMED_REQUEST,
                    "Request body could not be interpreted.");
        }
    }

    private String serialise(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("response could not be serialised", e);
        }
    }
}
