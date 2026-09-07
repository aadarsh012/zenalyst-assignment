package com.zenalyst.housing.intake;

import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.scheme.Scheme;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports a single paper row in its own transaction.
 *
 * <p>Separate from {@link PaperImportService} because {@link Propagation#REQUIRES_NEW} has to
 * cross a proxy boundary to take effect, and because the boundary is the point: each row commits
 * or rolls back alone. One unusable row among four thousand must not discard the other three
 * thousand nine hundred and ninety-nine.
 */
@Component
public class PaperRowImporter {

    private final ApplicationRepository applications;
    private final ApplicationNormaliser normaliser;
    private final AuditWriter audit;
    private final JdbcTemplate jdbc;
    private final IntakeService intake;
    private final Clock clock;

    public PaperRowImporter(
            ApplicationRepository applications,
            ApplicationNormaliser normaliser,
            AuditWriter audit,
            JdbcTemplate jdbc,
            IntakeService intake,
            Clock clock) {
        this.applications = applications;
        this.normaliser = normaliser;
        this.audit = audit;
        this.jdbc = jdbc;
        this.intake = intake;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Application importRow(
            Scheme scheme,
            SubmitApplicationRequest request,
            String paperReference,
            Instant receivedAt,
            String enteredBy) {

        Instant recordedAt = clock.instant();

        // receivedAt is when the form was handed in at the counter; recordedAt is now, which may
        // be weeks later. The deadline is tested against the former.
        NormalisedApplication normalised = normaliser.normalise(
                scheme.getCode(), ApplicationChannel.PAPER, request, receivedAt,
                ApplicationNormaliser.toLocalDate(recordedAt), paperReference, enteredBy);

        intake.requireWithinWindow(scheme, receivedAt);

        Long next = jdbc.queryForObject("SELECT nextval('application_no_seq')", Long.class);
        Application application = applications.save(Application.of(
                scheme.getId(), "%s-%06d".formatted(scheme.getCode(), next), normalised, recordedAt));

        audit.append(enteredBy, AuditAction.APPLICATION_RECEIVED, "application",
                application.getApplicationNo(), intake.auditPayload(application, scheme));

        return application;
    }
}
