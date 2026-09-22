package za.ac.ufh.safety.gbv;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A confidential GBV report, kept in its own table with its own access
 * rules per docs/api-contract.md section 7 - only gbv_officer and admin may
 * ever read case content. reporterUserId is kept even when anonymous is
 * true (the contract hides the reporter from the officer view, it does not
 * discard who reported), but nothing in any response ever includes it - see
 * GbvReportItem, which has no such field at all.
 *
 * referenceCode is the only handle an anonymous reporter has: not
 * guessable, not linked to an account, generated once at submission and
 * never reused (see GbvReportService.generateReferenceCode).
 */
@Entity
@Table(name = "gbv_reports",
       uniqueConstraints = @UniqueConstraint(name = "uk_gbv_reports_reference_code", columnNames = "reference_code"))
public class GbvReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "reference_code", nullable = false, length = 20)
    private String referenceCode;

    @Column(length = 2000)
    private String description;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    private Double latitude;

    private Double longitude;

    @Column(nullable = false)
    private Boolean anonymous;

    @Column(name = "contact_preference", length = 10)
    private String contactPreference;

    @Column(nullable = false, length = 20)
    private GbvReportStatus status;

    /** Null for an anonymous submission - never exposed in any response either way. */
    @Column(name = "reporter_user_id")
    private Long reporterUserId;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (submittedAt == null) submittedAt = now;
        lastUpdatedAt = now;
        if (status == null) status = GbvReportStatus.SUBMITTED;
    }

    @PreUpdate
    void onUpdate() {
        lastUpdatedAt = Instant.now();
    }

    public Long getReportId() { return reportId; }

    public String getReferenceCode() { return referenceCode; }
    public void setReferenceCode(String referenceCode) { this.referenceCode = referenceCode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Boolean getAnonymous() { return anonymous; }
    public void setAnonymous(Boolean anonymous) { this.anonymous = anonymous; }

    public String getContactPreference() { return contactPreference; }
    public void setContactPreference(String contactPreference) { this.contactPreference = contactPreference; }

    public GbvReportStatus getStatus() { return status; }
    public void setStatus(GbvReportStatus status) { this.status = status; }

    public Long getReporterUserId() { return reporterUserId; }
    public void setReporterUserId(Long reporterUserId) { this.reporterUserId = reporterUserId; }

    public Instant getSubmittedAt() { return submittedAt; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
}
