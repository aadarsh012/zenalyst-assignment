package com.zenalyst.housing.scheme;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A housing scheme: a fixed number of flats allocated under one published rule set.
 *
 * <p>Note that {@code applicationsCloseAt} is a submission deadline, not a data-entry
 * deadline. Paper applications are typed in after the window closes; treating the typing
 * date as the submission date would silently disqualify people who applied on time, which
 * is precisely the kind of defect this system exists to make impossible.
 */
@Entity
@Table(name = "scheme")
public class Scheme {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, updatable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "total_flats", nullable = false)
    private int totalFlats;

    @Column(name = "applications_open_at", nullable = false)
    private Instant applicationsOpenAt;

    @Column(name = "applications_close_at", nullable = false)
    private Instant applicationsCloseAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Scheme() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getTotalFlats() {
        return totalFlats;
    }

    public Instant getApplicationsOpenAt() {
        return applicationsOpenAt;
    }

    public Instant getApplicationsCloseAt() {
        return applicationsCloseAt;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
