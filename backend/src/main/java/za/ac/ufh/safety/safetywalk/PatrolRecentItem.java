package za.ac.ufh.safety.safetywalk;

import java.time.Instant;

public record PatrolRecentItem(
        Long patrolId,
        String zoneName,
        Double latitude,
        Double longitude,
        Instant recordedAt,
        long minutesAgo
) {
}