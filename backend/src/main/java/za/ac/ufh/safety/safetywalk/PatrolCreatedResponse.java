package za.ac.ufh.safety.safetywalk;

import java.time.Instant;

public record PatrolCreatedResponse(
        Long patrolId,
        Long zoneId,
        Instant recordedAt
) {
}