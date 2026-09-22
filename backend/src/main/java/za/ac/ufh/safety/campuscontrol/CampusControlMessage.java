package za.ac.ufh.safety.campuscontrol;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One message in a student's general (non-emergency) thread with campus
 * control. SOS and incidents already have their own dedicated flow and stay
 * entirely separate from this - this is for a question or an issue, not a
 * report.
 */
@Entity
@Table(name = "campus_control_messages")
public class CampusControlMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "student_user_id", nullable = false)
    private Long studentUserId;

    /** "student" or "campus_control". */
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
