package com.zenalyst.housing.identity;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.intake.Application;
import com.zenalyst.housing.intake.ApplicationRepository;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.error.ProblemType;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers questions about who an application belongs to, and records the decisions humans make
 * about the ambiguous cases.
 */
@Service
public class IdentityService {

    private final ApplicationRepository applications;
    private final SchemeRepository schemes;
    private final DuplicateLinkRepository links;
    private final DuplicateReviewRepository reviews;
    private final DeduplicationService deduplication;
    private final AuditWriter audit;
    private final Clock clock;

    public IdentityService(
            ApplicationRepository applications,
            SchemeRepository schemes,
            DuplicateLinkRepository links,
            DuplicateReviewRepository reviews,
            DeduplicationService deduplication,
            AuditWriter audit,
            Clock clock) {
        this.applications = applications;
        this.schemes = schemes;
        this.links = links;
        this.reviews = reviews;
        this.deduplication = deduplication;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Whether this application competes for a flat, and if not, which of the applicant's other
     * applications does.
     */
    @Transactional(readOnly = true)
    public IdentityResponse identityOf(String applicationNo) {
        Application application = applications.findByApplicationNo(applicationNo)
                .orElseThrow(() -> ApiException.notFound("application", applicationNo));

        List<IdentityResponse.PendingReview> pending = pendingReviewsInvolving(application.getId());

        return links.findByDuplicateApplicationId(application.getId())
                .map(link -> asDuplicate(application, link, pending))
                .orElseGet(() -> asCanonical(application, pending));
    }

    private IdentityResponse asDuplicate(
            Application application, DuplicateLink link, List<IdentityResponse.PendingReview> pending) {

        String canonicalNo = applicationNoOf(link.getCanonicalApplicationId());
        return new IdentityResponse(
                application.getApplicationNo(),
                IdentityResponse.Role.DUPLICATE,
                "This application was found to be another submission by the same person (%s). "
                        .formatted(link.getTier().description())
                        + "Application %s stands for you in the draw.".formatted(canonicalNo),
                canonicalNo,
                link.getTier(),
                link.getDecidedAt(),
                List.of(),
                pending);
    }

    private IdentityResponse asCanonical(
            Application application, List<IdentityResponse.PendingReview> pending) {

        List<IdentityResponse.Duplicate> duplicates = links
                .findByCanonicalApplicationId(application.getId()).stream()
                .map(link -> new IdentityResponse.Duplicate(
                        applicationNoOf(link.getDuplicateApplicationId()),
                        link.getTier(),
                        link.getTier().description(),
                        link.getDecidedAt()))
                .sorted((a, b) -> a.applicationNo().compareTo(b.applicationNo()))
                .toList();

        String reason = duplicates.isEmpty()
                ? "This application competes in the draw."
                : "This application competes in the draw. %d further submission(s) were found to be from the same person and set aside."
                        .formatted(duplicates.size());

        return new IdentityResponse(
                application.getApplicationNo(), IdentityResponse.Role.CANONICAL, reason,
                null, null, null, duplicates, pending);
    }

    @Transactional(readOnly = true)
    public List<ReviewSummary> reviewQueue(String schemeCode, ReviewStatus status) {
        Scheme scheme = scheme(schemeCode);
        List<DuplicateReview> found = status == null
                ? reviews.findBySchemeIdOrderByRaisedAtAsc(scheme.getId())
                : reviews.findBySchemeIdAndStatusOrderByRaisedAtAsc(scheme.getId(), status);
        return found.stream().map(this::summarise).toList();
    }

    /**
     * Records a decision and immediately rebuilds the scheme's links.
     *
     * <p>Rebuilding straight away rather than waiting for the next scheduled pass matters: between
     * confirming a duplicate and the links being rewritten, the register would still show two
     * people where the operator has just established there is one. Leaving that window open is how
     * a draw ends up running against a register nobody has re-checked since the last decision.
     */
    @Transactional
    public DeduplicationReport decide(UUID reviewId, ReviewDecisionRequest request, String decidedBy) {
        DuplicateReview review = reviews.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("duplicate review", reviewId.toString()));

        if (review.getStatus() != ReviewStatus.PENDING) {
            throw new ApiException(ProblemType.CONFLICT,
                    "This review was already decided as %s by %s."
                            .formatted(review.getStatus(), review.getDecidedBy()),
                    Map.of("reviewId", reviewId.toString(), "status", review.getStatus().name()));
        }

        Instant now = clock.instant();
        review.decide(request.outcome(), decidedBy, request.note(), now);
        reviews.saveAndFlush(review);

        audit.append(decidedBy, AuditAction.DUPLICATE_REVIEW_DECIDED, "duplicate_review",
                reviewId.toString(), decisionPayload(review));

        Scheme scheme = schemes.findById(review.getSchemeId())
                .orElseThrow(() -> ApiException.notFound("scheme", review.getSchemeId().toString()));
        return deduplication.run(scheme.getCode(), decidedBy);
    }

    private List<IdentityResponse.PendingReview> pendingReviewsInvolving(UUID applicationId) {
        List<IdentityResponse.PendingReview> pending = new ArrayList<>();
        for (DuplicateReview review : reviews.findByStatusInvolving(ReviewStatus.PENDING, applicationId)) {
            UUID other = review.getApplicationAId().equals(applicationId)
                    ? review.getApplicationBId()
                    : review.getApplicationAId();
            pending.add(new IdentityResponse.PendingReview(
                    review.getId().toString(), applicationNoOf(other),
                    review.getSimilarity().toPlainString()));
        }
        return pending;
    }

    private ReviewSummary summarise(DuplicateReview review) {
        Application first = applications.findById(review.getApplicationAId()).orElseThrow();
        Application second = applications.findById(review.getApplicationBId()).orElseThrow();

        // Stored in id order, which is arbitrary to a human. Presented in application-number
        // order, which is the order the operator will find them in every other screen.
        boolean inOrder = first.getApplicationNo().compareTo(second.getApplicationNo()) <= 0;
        Application a = inOrder ? first : second;
        Application b = inOrder ? second : first;

        return new ReviewSummary(
                review.getId().toString(),
                a.getApplicationNo(), b.getApplicationNo(),
                a.getFullName(), b.getFullName(),
                a.getDateOfBirth().toString(),
                review.getSimilarity().toPlainString(),
                review.getStatus(), review.getRaisedAt(),
                review.getDecidedAt(), review.getDecidedBy(), review.getDecisionNote());
    }

    private ObjectNode decisionPayload(DuplicateReview review) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("applicationNoA", applicationNoOf(review.getApplicationAId()));
        payload.put("applicationNoB", applicationNoOf(review.getApplicationBId()));
        payload.put("outcome", review.getStatus().name());
        payload.put("reviewId", review.getId().toString());
        payload.put("similarity", review.getSimilarity());
        if (review.getDecisionNote() != null) {
            payload.put("note", review.getDecisionNote());
        }
        return payload;
    }

    private String applicationNoOf(UUID applicationId) {
        return applications.findById(applicationId)
                .map(Application::getApplicationNo)
                .orElseThrow(() -> ApiException.notFound("application", applicationId.toString()));
    }

    private Scheme scheme(String schemeCode) {
        return schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));
    }
}
