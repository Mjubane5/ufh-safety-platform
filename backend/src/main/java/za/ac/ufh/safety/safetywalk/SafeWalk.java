package za.ac.ufh.safety.safetywalk;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A student walking a route from POST /api/routes/safe with live location
 * sharing turned on, as described in docs/api-contract.md's "Safe Walk
 * sessions" section. Not an incident: most of these simply reach "arrived"
 * and are never seen again, which is the point - campus control sees the
 * walk happening, not just an SOS after something has already gone wrong.
 */
@Entity
@Table(name = "safe_walks")
public class SafeWalk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "walk_id")
    private Long walkId;

    @Column(name = "student_user_id", nullable = false)
    private Long studentUserId;

    @Column(name = "origin_latitude", nullable = false)
    private Double originLatitude;

    @Column(name = "origin_longitude", nullable = false)
    private Double originLongitude;

    @Column(name = "destination_latitude", nullable = false)
    private Double destinationLatitude;

    @Column(name = "destination_longitude", nullable = false)
    private Double destinationLongitude;

    @Column(name = "current_latitude", nullable = false)
    private Double currentLatitude;

    @Column(name = "current_longitude", nullable = false)
    private Double currentLongitude;

    /** Whatever POST /routes/safe reported when the walk started. Never recomputed. */
    @Column(name = "safety_score")
    private Double safetyScore;

    @Column(nullable = false, length = 20)
    private SafeWalkStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SafeWalk() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (startedAt == null) startedAt = now;
        updatedAt = now;
        if (status == null) status = SafeWalkStatus.ACTIVE;
        // A walk starts where the student is, so the current position is the
        // origin until the first location update arrives.
        if (currentLatitude == null) currentLatitude = originLatitude;
        if (currentLongitude == null) currentLongitude = originLongitude;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getWalkId() { return walkId; }

    public Long getStudentUserId() { return studentUserId; }
    public void setStudentUserId(Long studentUserId) { this.studentUserId = studentUserId; }

    public Double getOriginLatitude() { return originLatitude; }
    public void setOriginLatitude(Double originLatitude) { this.originLatitude = originLatitude; }

    public Double getOriginLongitude() { return originLongitude; }
    public void setOriginLongitude(Double originLongitude) { this.originLongitude = originLongitude; }

    public Double getDestinationLatitude() { return destinationLatitude; }
    public void setDestinationLatitude(Double destinationLatitude) { this.destinationLatitude = destinationLatitude; }

    public Double getDestinationLongitude() { return destinationLongitude; }
    public void setDestinationLongitude(Double destinationLongitude) { this.destinationLongitude = destinationLongitude; }

    public Double getCurrentLatitude() { return currentLatitude; }
    public void setCurrentLatitude(Double currentLatitude) { this.currentLatitude = currentLatitude; }

    public Double getCurrentLongitude() { return currentLongitude; }
    public void setCurrentLongitude(Double currentLongitude) { this.currentLongitude = currentLongitude; }

    public Double getSafetyScore() { return safetyScore; }
    public void setSafetyScore(Double safetyScore) { this.safetyScore = safetyScore; }

    public SafeWalkStatus getStatus() { return status; }
    public void setStatus(SafeWalkStatus status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
