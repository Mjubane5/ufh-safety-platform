package za.ac.ufh.safety.wellness;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A student's request for an appointment with one of the wellness resources
 * from WellnessService.RESOURCES. resourceId 3 (Campus Health Centre) is
 * managed by health_officer; resourceId 1/2 by scu_officer - same table,
 * split by who may act on which row. See WellnessService.requireCanManage.
 */
@Entity
@Table(name = "wellness_bookings")
public class WellnessBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "student_user_id", nullable = false)
    private Long studentUserId;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "preferred_date", nullable = false)
    private LocalDate preferredDate;

    @Column(name = "preferred_slot", nullable = false, length = 20)
    private String preferredSlot;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 20)
    private WellnessBookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = WellnessBookingStatus.REQUESTED;
    }

    public Long getBookingId() { return bookingId; }

    public Long getStudentUserId() { return studentUserId; }
    public void setStudentUserId(Long studentUserId) { this.studentUserId = studentUserId; }

    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }

    public LocalDate getPreferredDate() { return preferredDate; }
    public void setPreferredDate(LocalDate preferredDate) { this.preferredDate = preferredDate; }

    public String getPreferredSlot() { return preferredSlot; }
    public void setPreferredSlot(String preferredSlot) { this.preferredSlot = preferredSlot; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public WellnessBookingStatus getStatus() { return status; }
    public void setStatus(WellnessBookingStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
}
