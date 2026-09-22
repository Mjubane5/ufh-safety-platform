package za.ac.ufh.safety.wellness;

import java.time.Instant;
import java.time.LocalDate;

public record WellnessBookingResponse(
        Long bookingId,
        Long studentUserId,
        String studentName,
        Long resourceId,
        LocalDate preferredDate,
        String preferredSlot,
        String note,
        WellnessBookingStatus status,
        Instant createdAt
) {

    // Public: HealthService (a different package) builds these for its own
    // Health Centre bookings, which share this table.
    public static WellnessBookingResponse of(WellnessBooking booking, String studentName) {
        return new WellnessBookingResponse(
                booking.getBookingId(),
                booking.getStudentUserId(),
                studentName,
                booking.getResourceId(),
                booking.getPreferredDate(),
                booking.getPreferredSlot(),
                booking.getNote(),
                booking.getStatus(),
                booking.getCreatedAt());
    }
}
