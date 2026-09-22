package za.ac.ufh.safety.safetywalk;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The straight-line-plus-one-detour route this codebase used before
 * CppRouteEngine existed. Kept as the fallback SafeRouteService reaches for
 * when the C++ engine is unavailable, slow, or fails - Safe Walk must keep
 * working even on a machine with no C++ toolchain (most teammates, most of
 * the time) or if the compiled binary is ever missing in production.
 *
 * Only ever produces a straight line, or a single detour waypoint around
 * whichever flagged hotspot intrudes on that line the most - it has no
 * concept of an actual road or path, which is exactly the limitation
 * CppRouteEngine exists to fix. Routing around several overlapping
 * hotspots at once (effectively building a visibility graph around every
 * obstacle) is out of scope for this fallback - a documented
 * simplification, not an oversight, since it is not the primary engine.
 */
@Component
public class GeometricRouteEngine implements RouteEngine {

    // A degree of latitude is ~111,320m everywhere; a degree of longitude
    // shrinks with cos(latitude). Good enough at campus scale (a few hundred
    // metres) to treat the walk as flat and use ordinary 2D geometry instead
    // of full geodesic maths - the curvature error over that distance is
    // negligible.
    private static final double METRES_PER_DEGREE_LATITUDE = 111_320.0;

    // How far past a hotspot's own radius the detour waypoint is pushed, so
    // the route does not just graze the edge of the danger zone.
    private static final double DETOUR_MARGIN_METRES = 15.0;

    @Override
    public List<SafeRouteResponse.RoutePoint> findPath(
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude,
            List<Hotspot> hotspots) {

        SafeRouteResponse.RoutePoint from = new SafeRouteResponse.RoutePoint(fromLatitude, fromLongitude);
        SafeRouteResponse.RoutePoint to = new SafeRouteResponse.RoutePoint(toLatitude, toLongitude);

        List<Hotspot> flagged = hotspots.stream()
                .filter(hotspot -> distanceToSegmentMetres(
                        hotspot.getLatitude(),
                        hotspot.getLongitude(),
                        fromLatitude,
                        fromLongitude,
                        toLatitude,
                        toLongitude
                ) <= hotspot.getRadiusMetres())
                .toList();

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
     * (t=0 or t=1) this is identical to a plain point-to-point distance.
     *
     * Package-private and static so SafeRouteService can reuse it to score
     * hotspot intrusion against whichever engine's polyline actually won,
     * one segment at a time, rather than duplicating this maths.
     */
    static double distanceToSegmentMetres(
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
