package za.ac.ufh.safety.safetywalk;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SafeRouteService {

    // A degree of latitude is ~111,320m everywhere; a degree of longitude
    // shrinks with cos(latitude). Good enough at campus scale (a few hundred
    // metres) to treat the walk as flat and use ordinary 2D geometry instead
    // of full geodesic maths - the curvature error over that distance is
    // negligible.
    private static final double METRES_PER_DEGREE_LATITUDE = 111_320.0;

    // How far past a hotspot's own radius the detour waypoint is pushed, so
    // the route does not just graze the edge of the danger zone.
    private static final double DETOUR_MARGIN_METRES = 15.0;

    // riskLevel -> how heavily it counts against safetyScore. Matches the
    // four levels docs/api-contract.md defines for a hotspot.
    private static final Map<String, Double> RISK_WEIGHTS = Map.of(
            "low", 0.15,
            "moderate", 0.35,
            "elevated", 0.6,
            "high", 0.9
    );
    private static final double DEFAULT_RISK_WEIGHT = 0.5; // an unrecognised riskLevel is treated as moderate-ish rather than ignored

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

        // A hotspot is "on the way" if it is within its own radius of any
        // point on the from->to line, not just of the two endpoints - a
        // danger zone in the middle of a long walk matters just as much as
        // one right at the start or end.
        List<Hotspot> flagged = hotspots.findAll().stream()
                .filter(hotspot -> distanceToSegmentMetres(
                        hotspot.getLatitude(),
                        hotspot.getLongitude(),
                        fromLatitude,
                        fromLongitude,
                        toLatitude,
                        toLongitude
                ) <= hotspot.getRadiusMetres())
                .toList();

        double safetyScore = computeSafetyScore(
                flagged,
                fromLatitude,
                fromLongitude,
                toLatitude,
                toLongitude
        );

        List<SafeRouteResponse.RoutePoint> points = buildRoutePoints(
                flagged,
                fromLatitude,
                fromLongitude,
                toLatitude,
                toLongitude
        );

        List<Long> avoidedHotspots = flagged.stream()
                .map(Hotspot::getHotspotId)
                .toList();

        int estimatedSeconds = (int) Math.ceil(distance / 1.33);

        return new SafeRouteResponse(
                distance,
                estimatedSeconds,
                safetyScore,
                avoidedHotspots,
                points
        );
    }

    /**
     * safetyScore starts at 1.0 (perfectly safe) and loses ground for every
     * flagged hotspot: riskWeight(hotspot) * howMuchItIntrudes, where
     * "intrudes" is the fraction of its own radius that actually overlaps
     * the direct path (0 = just barely touches the edge, 1 = the path goes
     * straight through its centre). Penalties are summed and capped so one
     * severe hotspot cannot push the score below a floor of 0.05 - a route
     * is never reported as literally zero.
     */
    private double computeSafetyScore(
            List<Hotspot> flagged,
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude) {

        if (flagged.isEmpty()) {
            return 1.0;
        }

        double totalPenalty = 0.0;

        for (Hotspot hotspot : flagged) {
            double distanceToPath = distanceToSegmentMetres(
                    hotspot.getLatitude(),
                    hotspot.getLongitude(),
                    fromLatitude,
                    fromLongitude,
                    toLatitude,
                    toLongitude
            );
            double intrusion = Math.max(
                    0.0,
                    (hotspot.getRadiusMetres() - distanceToPath) / hotspot.getRadiusMetres()
            );
            double riskWeight = RISK_WEIGHTS.getOrDefault(hotspot.getRiskLevel(), DEFAULT_RISK_WEIGHT);
            totalPenalty += riskWeight * intrusion;
        }

        double score = 1.0 - Math.min(1.0, totalPenalty);
        return Math.max(0.05, score);
    }

    /**
     * The direct line when nothing is flagged; otherwise a single detour
     * waypoint nudged around whichever flagged hotspot intrudes on the path
     * the most. Routing around several overlapping hotspots at once is a
     * harder pathfinding problem (effectively building a visibility graph
     * around every obstacle) that is out of scope here - this is a
     * documented simplification, not an oversight.
     */
    private List<SafeRouteResponse.RoutePoint> buildRoutePoints(
            List<Hotspot> flagged,
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude) {

        SafeRouteResponse.RoutePoint from = new SafeRouteResponse.RoutePoint(fromLatitude, fromLongitude);
        SafeRouteResponse.RoutePoint to = new SafeRouteResponse.RoutePoint(toLatitude, toLongitude);

        Optional<Hotspot> mostSignificant = flagged.stream()
                .max(Comparator.comparingDouble(hotspot ->
                        hotspot.getRadiusMetres() - distanceToSegmentMetres(
                                hotspot.getLatitude(),
                                hotspot.getLongitude(),
                                fromLatitude,
                                fromLongitude,
                                toLatitude,
                                toLongitude
                        )
                ));

        if (mostSignificant.isEmpty()) {
            return List.of(from, to);
        }

        SafeRouteResponse.RoutePoint detour = detourAround(
                mostSignificant.get(),
                fromLatitude,
                fromLongitude,
                toLatitude,
                toLongitude
        );
        return List.of(from, detour, to);
    }

    /**
     * A waypoint pushed sideways off the from->to line, away from the
     * hotspot's centre, far enough to clear its radius plus a margin.
     */
    private static SafeRouteResponse.RoutePoint detourAround(
            Hotspot hotspot,
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude) {

        double metresPerDegreeLongitude = METRES_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(fromLatitude));

        double ax = 0.0;
        double ay = 0.0;
        double bx = (toLongitude - fromLongitude) * metresPerDegreeLongitude;
        double by = (toLatitude - fromLatitude) * METRES_PER_DEGREE_LATITUDE;
        double hx = (hotspot.getLongitude() - fromLongitude) * metresPerDegreeLongitude;
        double hy = (hotspot.getLatitude() - fromLatitude) * METRES_PER_DEGREE_LATITUDE;

        double segmentDx = bx - ax;
        double segmentDy = by - ay;
        double segmentLengthSquared = segmentDx * segmentDx + segmentDy * segmentDy;

        double t = segmentLengthSquared == 0.0
                ? 0.0
                : ((hx - ax) * segmentDx + (hy - ay) * segmentDy) / segmentLengthSquared;
        // Keep the detour waypoint away from the very ends of the walk, so
        // it reads as a bend in the middle rather than a jump right at the
        // start or finish.
        double clampedT = Math.max(0.15, Math.min(0.85, t));

        double closestX = ax + clampedT * segmentDx;
        double closestY = ay + clampedT * segmentDy;

        double awayX = closestX - hx;
        double awayY = closestY - hy;
        double awayLength = Math.sqrt(awayX * awayX + awayY * awayY);

        if (awayLength < 0.01) {
            // The hotspot sits almost exactly on the line, so "away from
            // its centre" is undefined - push perpendicular to the
            // direction of travel instead.
            double segmentLength = Math.sqrt(segmentLengthSquared);
            if (segmentLength == 0.0) {
                awayX = 1.0;
                awayY = 0.0;
            } else {
                awayX = -segmentDy / segmentLength;
                awayY = segmentDx / segmentLength;
            }
            awayLength = 1.0;
        }

        double pushMetres = hotspot.getRadiusMetres() + DETOUR_MARGIN_METRES;
        double detourX = hx + (awayX / awayLength) * pushMetres;
        double detourY = hy + (awayY / awayLength) * pushMetres;

        double detourLatitude = fromLatitude + detourY / METRES_PER_DEGREE_LATITUDE;
        double detourLongitude = fromLongitude + detourX / metresPerDegreeLongitude;

        return new SafeRouteResponse.RoutePoint(detourLatitude, detourLongitude);
    }

    /**
     * Shortest distance in metres from a point to the from->to line segment
     * (not the infinite line) - projects everything into a local flat
     * metre grid centred on "from", clamps the projection onto the
     * segment, then measures ordinary 2D distance. At endpoint clamping
     * (t=0 or t=1) this is identical to a plain point-to-point distance, so
     * it strictly covers the old "near either end" check as well.
     */
    private static double distanceToSegmentMetres(
            double pointLatitude,
            double pointLongitude,
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude) {

        double metresPerDegreeLongitude = METRES_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(fromLatitude));

        double ax = 0.0;
        double ay = 0.0;
        double bx = (toLongitude - fromLongitude) * metresPerDegreeLongitude;
        double by = (toLatitude - fromLatitude) * METRES_PER_DEGREE_LATITUDE;
        double px = (pointLongitude - fromLongitude) * metresPerDegreeLongitude;
        double py = (pointLatitude - fromLatitude) * METRES_PER_DEGREE_LATITUDE;

        double segmentDx = bx - ax;
        double segmentDy = by - ay;
        double segmentLengthSquared = segmentDx * segmentDx + segmentDy * segmentDy;

        double t = segmentLengthSquared == 0.0
                ? 0.0
                : ((px - ax) * segmentDx + (py - ay) * segmentDy) / segmentLengthSquared;
        double clampedT = Math.max(0.0, Math.min(1.0, t));

        double closestX = ax + clampedT * segmentDx;
        double closestY = ay + clampedT * segmentDy;

        double dx = px - closestX;
        double dy = py - closestY;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
