package com.zenalyst.housing.intake;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.normalisation.FieldViolation;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.error.ProblemType;
import com.zenalyst.housing.platform.error.ValidationFailedException;
import com.zenalyst.housing.scheme.Scheme;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports a file of paper applications that were typed up after the fact.
 *
 * <p>Three properties matter here, and they are the reason this is not simply a loop over
 * {@code save()}.
 *
 * <p><strong>Partial failure is the normal case.</strong> Some rows will be unusable. Those rows
 * are reported with the field and reason; the rest are accepted. Rejecting the file wholesale
 * would mean an operator fixing one typo at a time across several days.
 *
 * <p><strong>Re-uploading is safe.</strong> The paper receipt reference is unique per scheme, so
 * a corrected file containing rows that already landed skips them rather than importing them
 * twice. Without that, the tool for fixing bad rows would itself manufacture duplicates.
 *
 * <p><strong>The receipt date is the submission date.</strong> A form handed in on the 3rd and
 * typed in on the 19th was submitted on the 3rd, and is judged against the deadline on that
 * basis.
 */
@Service
public class PaperImportService {

    static final Set<String> REQUIRED_COLUMNS = Set.of(
            "paper_reference", "received_at", "full_name", "date_of_birth", "government_id",
            "address_line", "category", "gender");

    private static final int MAX_ROWS = 10_000;

    private final IntakeService intake;
    private final PaperRowImporter rowImporter;
    private final AuditWriter audit;
    /**
     * The batch audit event needs a transaction of its own. Import itself is deliberately not
     * transactional — each row commits alone — so there is no ambient transaction for the audit
     * append to join, and an annotation here would be bypassed anyway because the call is
     * internal to this bean.
     */
    private final TransactionTemplate transactions;

    public PaperImportService(
            IntakeService intake,
            PaperRowImporter rowImporter,
            AuditWriter audit,
            PlatformTransactionManager transactionManager) {
        this.intake = intake;
        this.rowImporter = rowImporter;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ImportReport importCsv(String schemeCode, Reader csv, String enteredBy) {
        Scheme scheme = intake.openScheme(schemeCode);
        List<ImportRowOutcome> outcomes = new ArrayList<>();

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .get();

        try (CSVParser parser = CSVParser.parse(csv, format)) {

            requireColumns(parser.getHeaderMap().keySet());

            for (CSVRecord record : parser) {
                if (outcomes.size() >= MAX_ROWS) {
                    throw new ApiException(ProblemType.VALIDATION_FAILED,
                            "File exceeds the maximum of %,d rows. Split it and import in parts."
                                    .formatted(MAX_ROWS));
                }
                outcomes.add(importOne(scheme, record, enteredBy));
            }
        } catch (IOException e) {
            throw new ApiException(ProblemType.MALFORMED_REQUEST,
                    "The uploaded file could not be read as CSV: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemType.MALFORMED_REQUEST,
                    "The uploaded file is not valid CSV: " + e.getMessage());
        }

        ImportReport report = ImportReport.of(scheme.getCode(), outcomes);
        recordBatch(scheme, report, enteredBy);
        return report;
    }

    private ImportRowOutcome importOne(Scheme scheme, CSVRecord record, String enteredBy) {
        // Commons CSV numbers the header as record 1, so a data row's number already matches what
        // a spreadsheet shows.
        int rowNumber = (int) record.getRecordNumber() + 1;
        String paperReference = value(record, "paper_reference");

        List<FieldViolation> violations = new ArrayList<>();
        if (paperReference == null || paperReference.isBlank()) {
            violations.add(FieldViolation.of("paper_reference", "REQUIRED",
                    "must be provided; without it this row cannot be traced to a physical form"));
        }

        Instant receivedAt = null;
        try {
            receivedAt = parseReceivedAt(value(record, "received_at"));
        } catch (ValidationFailedException e) {
            violations.addAll(e.violations());
        }

        if (!violations.isEmpty()) {
            return ImportRowOutcome.rejected(rowNumber, paperReference, violations);
        }

        SubmitApplicationRequest request = new SubmitApplicationRequest(
                value(record, "full_name"),
                value(record, "date_of_birth"),
                value(record, "government_id"),
                value(record, "phone"),
                value(record, "email"),
                value(record, "address_line"),
                value(record, "ward_code"),
                value(record, "category"),
                value(record, "gender"),
                value(record, "local_resident"),
                value(record, "disability"),
                value(record, "ex_serviceperson"),
                value(record, "annual_income"));

        try {
            Application application = rowImporter.importRow(
                    scheme, request, paperReference, receivedAt, enteredBy);
            return ImportRowOutcome.accepted(rowNumber, paperReference, application.getApplicationNo());

        } catch (ValidationFailedException e) {
            return ImportRowOutcome.rejected(rowNumber, paperReference, e.violations());

        } catch (DataIntegrityViolationException e) {
            // A repeated paper reference means the row was imported by an earlier run, which is a
            // success from the operator's point of view: the form is in the register exactly once.
            //
            // Any *other* integrity violation is a genuine problem with the row, and reporting it
            // as "already imported" would tell the operator their data was fine when it was not.
            // This branch previously did exactly that, and hid a set of rows whose receipt dates
            // were in the future.
            if (isRepeatedPaperReference(e)) {
                return ImportRowOutcome.alreadyImported(rowNumber, paperReference, null);
            }
            return ImportRowOutcome.rejected(rowNumber, paperReference, List.of(
                    FieldViolation.of("row", "REJECTED_BY_DATABASE", describeConstraint(e))));

        } catch (ApiException e) {
            return ImportRowOutcome.rejected(rowNumber, paperReference,
                    List.of(FieldViolation.of("received_at", "OUTSIDE_WINDOW", e.getMessage())));
        }
    }

    private static boolean isRepeatedPaperReference(DataIntegrityViolationException e) {
        return describeConstraint(e).contains("application_paper_reference_idx");
    }

    /**
     * The database's own account of what was wrong, which names the constraint.
     *
     * <p>Passed through to the operator rather than replaced with something friendlier: a violated
     * check constraint is a statement about their data, and "submitted_before_recorded" tells
     * somebody looking at a spreadsheet far more than "invalid row" would.
     */
    private static String describeConstraint(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String message = cause.getMessage() == null ? e.toString() : cause.getMessage();
        return message.length() > 400 ? message.substring(0, 400) : message;
    }

    /**
     * The receipt date is a date, not an instant — a counter stamps a day, not a time. It is
     * anchored at the start of that day in UTC, which is the reading most favourable to the
     * applicant when the date falls on the deadline itself.
     */
    private Instant parseReceivedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationFailedException(List.of(FieldViolation.of(
                    "received_at", "REQUIRED", "must be the date the form was handed in")));
        }
        try {
            return LocalDate.parse(raw.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ValidationFailedException(List.of(FieldViolation.of(
                    "received_at", "UNPARSEABLE_DATE", "must be an ISO date such as 2026-03-14")));
        }
    }

    private void requireColumns(Set<String> present) {
        List<String> missing = REQUIRED_COLUMNS.stream()
                .filter(column -> present.stream().noneMatch(p -> p.equalsIgnoreCase(column)))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ProblemType.VALIDATION_FAILED,
                    "The file is missing required column(s): " + String.join(", ", missing),
                    java.util.Map.of("missingColumns", missing));
        }
    }

    private static String value(CSVRecord record, String column) {
        return record.isMapped(column) && record.isSet(column) ? emptyToNull(record.get(column)) : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * One audit event per batch, not per rejected row. The accepted rows each recorded their own
     * acceptance; what the chain additionally needs is evidence of the operation as a whole —
     * who ran it, how many rows it touched, and how many it turned away.
     */
    void recordBatch(Scheme scheme, ImportReport report, String enteredBy) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("accepted", report.accepted());
        payload.put("alreadyImported", report.alreadyImported());
        payload.put("rejected", report.rejected());
        payload.put("schemeCode", scheme.getCode());
        payload.put("totalRows", report.totalRows());

        transactions.executeWithoutResult(status -> audit.append(
                enteredBy, AuditAction.APPLICATION_BATCH_IMPORTED, "scheme",
                scheme.getCode(), payload));
    }
}
