package za.ac.ufh.safety.health;

import java.time.Instant;
import java.util.List;
import za.ac.ufh.safety.wellness.WellnessBookingResponse;

/**
 * GET /api/health/queue - health_officer/admin only. Same shape as the SCU
 * queue: every student with a Health Centre message or a resourceId-3
 * booking, most recent activity first.
 */
public record HealthQueueResponse(List<Item> items) {

    public record Item(
            Long studentUserId,
            String studentName,
            Instant lastActivityAt,
            boolean hasUnread,
            List<WellnessBookingResponse> bookings
    ) {
    }
}
