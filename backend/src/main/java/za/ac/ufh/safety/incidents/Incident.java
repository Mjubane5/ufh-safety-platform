package za.ac.ufh.safety.incidents;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One reported incident. SOS is not a separate table: it is a row with
 * type "sos", as described in section 3 of docs/api-contract.md.
 *
 * Field names here follow the contract's JSON names so the mapping to the
 * response body stays obvious. Column names are snake_case, matching User.
 */
@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "incident_id")
    private Long incidentId;

    /**
     * The student who filed the report. Kept even when anonymous is true:
     * the contract hides the reporter from responders, it does not discard
     * who reported. Campus control and admin can still audit it.
     */
    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(length = 1000)
    private String description;

    private Double latitude;

    private Double longitude;

    private Double accuracy;

    /**
     * "device" when the report carried coordinates, "none" when it did not.
     * Derived below rather than set by a caller, so it can never disagree
     * with the coordinates actually stored.
     */
    @Column(name = "location_source", nullable = false, length = 10)
    private String locationSource;

    @Column(nullable = false)
    private Boolean anonymous;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private Integer priority;

    /** Null until the incident is assigned. */
    @Column(name = "assigned_responder_id")
    private Long assignedResponderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Incident() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = "reported";
        if (anonymous == null) anonymous = Boolean.FALSE;
        applyLocationSource();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        applyLocationSource();
    }

    private void applyLocationSource() {
        locationSource = (latitude != null && longitude != null) ? "device" : "none";
    }

    public Long getIncidentId() { return incidentId; }

    public Long getReporterUserId() { return reporterUserId; }
    public void setReporterUserId(Long reporterUserId) { this.reporterUserId = reporterUserId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Double getAccuracy() { return accuracy; }
    public void setAccuracy(Double accuracy) { this.accuracy = accuracy; }

    /** No setter: derived from the coordinates when the row is written. */
    public String getLocationSource() { return locationSource; }

    public Boolean getAnonymous() { return anonymous; }
    public void setAnonymous(Boolean anonymous) { this.anonymous = anonymous; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Long getAssignedResponderId() { return assignedResponderId; }
    public void setAssignedResponderId(Long assignedResponderId) { this.assignedResponderId = assignedResponderId; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
