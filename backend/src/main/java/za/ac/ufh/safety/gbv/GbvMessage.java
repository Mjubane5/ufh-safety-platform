package za.ac.ufh.safety.gbv;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One message in a GBV report's chat. Deliberately keyed by referenceCode
 * (a String), not a userId - see docs/api-contract.md section 7a: scoped to
 * the report, never to a reporter account, so nothing here can ever be
 * traced back to who filed the report.
 */
@Entity
@Table(name = "gbv_messages")
public class GbvMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "reference_code", nullable = false, length = 20)
    private String referenceCode;

    /** "reporter" or "gbv_officer". */
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

    public String getReferenceCode() { return referenceCode; }
    public void setReferenceCode(String referenceCode) { this.referenceCode = referenceCode; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Instant getSentAt() { return sentAt; }
}
