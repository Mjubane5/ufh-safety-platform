package za.ac.ufh.safety.campuscontrol;

import java.time.Instant;
import java.util.List;

/** GET /api/campus-control/queue - campus_control/admin only. */
public record CampusControlQueueResponse(List<Item> items) {

    public record Item(
            Long studentUserId,
            String studentName,
            Instant lastActivityAt,
            boolean hasUnread
    ) {
    }
}
