package za.ac.ufh.safety.safetywalk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record StartSafeWalkRequest(

        @NotNull
        @Valid
        Coordinate origin,

        @NotNull
        @Valid
        Coordinate destination,

        // Whatever POST /routes/safe returned for this origin/destination - only
        // safetyScore is actually persisted, so the session records the score it
        // started with rather than the backend recomputing it.
        RouteInfo route
) {

        public record Coordinate(

                @NotNull
                @DecimalMin("-90.0")
                @DecimalMax("90.0")
                Double latitude,

                @NotNull
                @DecimalMin("-180.0")
                @DecimalMax("180.0")
                Double longitude
        ) {
        }

        public record RouteInfo(
                Double distanceMetres,
                Integer estimatedSeconds,
                Double safetyScore
        ) {
        }
}
