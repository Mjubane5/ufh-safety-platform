package za.ac.ufh.safety.safetywalk;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/safewalks/active - campus_control/admin only. Only ACTIVE walks
 * are listed; one that has arrived or been cancelled is not a live safety
 * concern any more and drops off this list.
 */
public record ActiveSafeWalksResponse(List<Item> items) {

    public record Item(
            Long walkId,
            Long studentUserId,
            String studentName,
            Coordinate destination,
            Coordinate currentLocation,
            Double safetyScore,
            Instant startedAt,
            Instant updatedAt
    ) {
    }

    public record Coordinate(Double latitude, Double longitude) {
    }
}
