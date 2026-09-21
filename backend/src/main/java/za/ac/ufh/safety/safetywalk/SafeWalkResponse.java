package za.ac.ufh.safety.safetywalk;

import java.time.Instant;

/**
 * The shape returned by POST /api/safewalks, PATCH .../location,
 * POST .../arrived and POST .../cancel - contract section "Safe Walk
 * sessions". Same response shape from every one of those, the updated walk.
 */
public record SafeWalkResponse(
        Long walkId,
        SafeWalkStatus status,
        Coordinate origin,
        Coordinate destination,
        Coordinate currentLocation,
        Double safetyScore,
        Instant startedAt,
        Instant updatedAt
) {

    public record Coordinate(Double latitude, Double longitude) {
    }

    static SafeWalkResponse of(SafeWalk walk) {
        return new SafeWalkResponse(
                walk.getWalkId(),
                walk.getStatus(),
                new Coordinate(walk.getOriginLatitude(), walk.getOriginLongitude()),
                new Coordinate(walk.getDestinationLatitude(), walk.getDestinationLongitude()),
                new Coordinate(walk.getCurrentLatitude(), walk.getCurrentLongitude()),
                walk.getSafetyScore(),
                walk.getStartedAt(),
                walk.getUpdatedAt());
    }
}
