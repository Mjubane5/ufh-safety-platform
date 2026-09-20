package za.ac.ufh.safety.safetywalk;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SafeRouteService {

    private final HotspotRepository hotspots;

    public SafeRouteService(HotspotRepository hotspots) {
        this.hotspots = hotspots;
    }

    public SafeRouteResponse findSafeRoute(SafeRouteRequest request) {

        double fromLatitude = request.from().latitude();
        double fromLongitude = request.from().longitude();

        double toLatitude = request.to().latitude();
        double toLongitude = request.to().longitude();

        double distance = SafetyWalkService.distanceMetres(
                fromLatitude,
                fromLongitude,
                toLatitude,
                toLongitude
        );

        List<Long> avoidedHotspots = new ArrayList<>();

        hotspots.findAll().forEach(hotspot -> {

            double fromDistance =
                    SafetyWalkService.distanceMetres(
                            fromLatitude,
                            fromLongitude,
                            hotspot.getLatitude(),
                            hotspot.getLongitude()
                    );

            double toDistance =
                    SafetyWalkService.distanceMetres(
                            toLatitude,
                            toLongitude,
                            hotspot.getLatitude(),
                            hotspot.getLongitude()
                    );

            if (fromDistance <= hotspot.getRadiusMetres()
                    || toDistance <= hotspot.getRadiusMetres()) {

                avoidedHotspots.add(hotspot.getHotspotId());
            }
        });

        double safetyScore;

        if (avoidedHotspots.isEmpty()) {
            safetyScore = 1.0;
        } else {
            safetyScore = 0.78;
        }

        int estimatedSeconds =
                (int) Math.ceil(distance / 1.33);

        List<SafeRouteResponse.RoutePoint> points =
                List.of(
                        new SafeRouteResponse.RoutePoint(
                                fromLatitude,
                                fromLongitude
                        ),
                        new SafeRouteResponse.RoutePoint(
                                toLatitude,
                                toLongitude
                        )
                );

        return new SafeRouteResponse(
                distance,
                estimatedSeconds,
                safetyScore,
                avoidedHotspots,
                points
        );
    }
}