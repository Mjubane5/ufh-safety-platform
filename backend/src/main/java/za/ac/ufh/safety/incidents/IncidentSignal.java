package za.ac.ufh.safety.incidents;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One derived distress-detection event, from docs/api-contract.md's
 * "Incident live signals" section. Only ever a transcript line, a sound
 * label, or a facial-expression label - never raw audio or video, which
 * never leaves the reporter's device in the first place. See
 * frontend/js/distress-detection.js.
 */
@Entity
@Table(name = "incident_signals")
public class IncidentSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "signal_id")
    private Long signalId;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(nullable = false, length = 20)
    private SignalType type;

    @Column(length = 50)
    private String label;

    private Double confidence;

    @Column(length = 500)
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getSignalId() { return signalId; }

    public Long getIncidentId() { return incidentId; }
    public void setIncidentId(Long incidentId) { this.incidentId = incidentId; }

    public SignalType getType() { return type; }
    public void setType(SignalType type) { this.type = type; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Instant getCreatedAt() { return createdAt; }
}
