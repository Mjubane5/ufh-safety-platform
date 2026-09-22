package za.ac.ufh.safety.wellness;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One message in a student's ongoing thread with the Student Counselling
 * Unit. One thread per student, not per booking - see api.js's comment on
 * why a continuous conversation beats a fresh thread every time.
 */
@Entity
@Table(name = "wellness_messages")
public class WellnessMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "student_user_id", nullable = false)
    private Long studentUserId;

    /** "student" or "scu" - who sent it, not who it is about. */
    @Column(nullable = false, length = 10)
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
