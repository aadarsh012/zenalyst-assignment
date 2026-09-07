package com.zenalyst.housing.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A submitted application.
 *
 * <p>Applications are never modified and never deleted. A duplicate is not removed, it is linked;
 * an ineligible application is not discarded, it is marked with its reasons. Four thousand people
 * applied, the published result must account for all four thousand, and a row that has quietly
 * vanished cannot be accounted for.
 *
 * <p>There are consequently no setters. Corrections arrive as new records, and the history of
 * what was believed and when is preserved.
 */
@Entity
@Table(name = "application")
public class Application {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scheme_id", nullable = false, updatable = false)
    private UUID schemeId;

    @Column(name = "application_no", nullable = false, unique = true, updatable = false)
    private String applicationNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private ApplicationChannel channel;

    /** The submission exactly as received, government id redacted. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, updatable = false)
    private String rawPayload;

    @Column(name = "full_name", nullable = false, updatable = false)
    private String fullName;

    @Column(name = "name_key", nullable = false, updatable = false)
    private String nameKey;

    @Column(name = "date_of_birth", nullable = false, updatable = false)
    private LocalDate dateOfBirth;

    @Column(name = "phone_e164", updatable = false)
    private String phoneE164;

    @Column(name = "email", updatable = false)
    private String email;

    @Column(name = "email_key", updatable = false)
    private String emailKey;

    @Column(name = "address_line", nullable = false, updatable = false)
    private String addressLine;

    @Column(name = "ward_code", updatable = false)
    private String wardCode;

    @Column(name = "government_id_hash", nullable = false, updatable = false)
    private String governmentIdToken;

    @Column(name = "government_id_last4", nullable = false, updatable = false)
    private String governmentIdLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, updatable = false)
    private Gender gender;

    @Column(name = "is_local_resident", nullable = false, updatable = false)
    private boolean localResident;

    @Column(name = "has_disability", nullable = false, updatable = false)
    private boolean disability;

    @Column(name = "is_ex_serviceperson", nullable = false, updatable = false)
    private boolean exServiceperson;

    @Column(name = "annual_income", updatable = false)
    private BigDecimal annualIncome;

    /** When the applicant applied. For paper, the counter receipt date. Deadlines use this. */
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    /** When this row was written. For paper, later — sometimes much later — than submittedAt. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "paper_reference", updatable = false)
    private String paperReference;

    @Column(name = "entered_by", updatable = false)
    private String enteredBy;

    protected Application() {
        // for JPA
    }

    public static Application of(
            UUID schemeId, String applicationNo, NormalisedApplication normalised, Instant recordedAt) {

        Application application = new Application();
        application.id = UUID.randomUUID();
        application.schemeId = schemeId;
        application.applicationNo = applicationNo;
        application.channel = normalised.channel();
        application.rawPayload = normalised.rawPayload();
        application.fullName = normalised.fullName();
        application.nameKey = normalised.nameKey();
        application.dateOfBirth = normalised.dateOfBirth();
        application.phoneE164 = normalised.phoneE164();
        application.email = normalised.email();
        application.emailKey = normalised.emailKey();
        application.addressLine = normalised.addressLine();
        application.wardCode = normalised.wardCode();
        application.governmentIdToken = normalised.governmentIdToken();
        application.governmentIdLast4 = normalised.governmentIdLast4();
        application.category = normalised.category();
        application.gender = normalised.gender();
        application.localResident = normalised.localResident();
        application.disability = normalised.disability();
        application.exServiceperson = normalised.exServiceperson();
        application.annualIncome = normalised.annualIncome();
        application.submittedAt = normalised.submittedAt();
        application.recordedAt = recordedAt;
        application.paperReference = normalised.paperReference();
        application.enteredBy = normalised.enteredBy();
        return application;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSchemeId() {
        return schemeId;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public ApplicationChannel getChannel() {
        return channel;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public String getFullName() {
        return fullName;
    }

    public String getNameKey() {
        return nameKey;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public String getEmail() {
        return email;
    }

    public String getEmailKey() {
        return emailKey;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public String getWardCode() {
        return wardCode;
    }

    public String getGovernmentIdToken() {
        return governmentIdToken;
    }

    public String getGovernmentIdLast4() {
        return governmentIdLast4;
    }

    public Category getCategory() {
        return category;
    }

    public Gender getGender() {
        return gender;
    }

    public boolean isLocalResident() {
        return localResident;
    }

    public boolean hasDisability() {
        return disability;
    }

    public boolean isExServiceperson() {
        return exServiceperson;
    }

    public BigDecimal getAnnualIncome() {
        return annualIncome;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getPaperReference() {
        return paperReference;
    }

    public String getEnteredBy() {
        return enteredBy;
    }
}
