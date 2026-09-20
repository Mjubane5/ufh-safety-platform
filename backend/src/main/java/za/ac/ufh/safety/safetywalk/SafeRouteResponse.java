package za.ac.ufh.safety.safetywalk;

import java.util.List;

public record SafeRouteResponse(
        double distanceMetres,
        int estimatedSeconds,
        double safetyScore,
        List<Long> avoidedHotspots,
        List<RoutePoint> points
) {

    public record RoutePoint(
            double latitude,
            double longitude
    ) {
    }
}
