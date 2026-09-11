package za.ac.ufh.safety.responders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The duty record for one responder: which team, whether they are free, and
 * where they last reported being.
 *
 * responder_id is the same number as users.user_id rather than a new
 * generated key. A responder is a user with extra duty information, not a
 * separate person, and Incident.assignedResponderId already holds a user id —
 * two different id spaces for the same human is how you end up dispatching
 * responder 5 to a call meant for user 5.
 *
 * This table holds no student or incident data, so it needs no special
 * access rules of its own beyond the role check on the endpoints.
 */
@Entity
@Table(name = "responders")
public class Responder {

    @Id
    @Column(name = "responder_id")
    private Long responderId;

    /** Free text for now: campus_security, medical, gbv_response. */
    @Column(nullable = false, length = 40)
    private String team;

    @Column(nullable = false, length = 20)
    private ResponderStatus status;

    // Nullable on purpose. A responder who has not checked in yet has no
    // position, and the contract says an absent value is null. Storing 0
    // would place them in the Gulf of Guinea and make them look nearest to
    // nothing in particular.
    private Double latitude;

    private Double longitude;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    private void touch() {
        updatedAt = Instant.now();
    }

    public Long getResponderId() { return responderId; }
    public void setResponderId(Long responderId) { this.responderId = responderId; }

    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }

    public ResponderStatus getStatus() { return status; }
    public void setStatus(ResponderStatus status) { this.status = status; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * True when this responder can be sent to a call that needs a position.
     * Being available is not enough — a responder with no known location
     * cannot be measured against an incident, so proximity cannot rank them.
     */
    public boolean isLocatable() {
        return status == ResponderStatus.AVAILABLE && latitude != null && longitude != null;
    }
}
