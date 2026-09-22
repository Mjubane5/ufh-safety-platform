package za.ac.ufh.safety.health;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One message in a student's ongoing thread with the Campus Health Centre.
 * Deliberately a separate table/thread from WellnessMessage (SCU) even
 * though both are "wellness support" - a physical-health question must
 * never land in a counsellor's queue or vice versa.
 */
@Entity
@Table(name = "health_messages")
public class HealthMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "student_user_id", nullable = false)
    private Long studentUserId;

    /** "student" or "health_officer". */
    @Column(nullable = false, length = 20)
    private String sender;

    @Column(nullable = false, length = 500)
    private String text;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (sentAt == null) sentAt = Instant.now();
    }

    public Long getMessageId() { return messageId; }

    public Long getStudentUserId() { return studentUserId; }
    public void setStudentUserId(Long studentUserId) { this.studentUserId = studentUserId; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Instant getSentAt() { return sentAt; }
}
