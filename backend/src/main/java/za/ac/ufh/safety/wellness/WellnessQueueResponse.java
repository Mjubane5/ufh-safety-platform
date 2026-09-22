package za.ac.ufh.safety.wellness;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/wellness/queue - scu_officer/admin only. Every student who has
 * either booked (resourceId != 3, the Health Centre's own queue) or sent a
 * message, most recent activity first.
 */
public record WellnessQueueResponse(List<Item> items) {

    public record Item(
            Long studentUserId,
            String studentName,
            Instant lastActivityAt,
            boolean hasUnread,
            List<WellnessBookingResponse> bookings
    ) {
    }
}
