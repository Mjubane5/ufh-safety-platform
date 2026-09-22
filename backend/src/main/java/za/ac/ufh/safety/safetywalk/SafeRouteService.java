package za.ac.ufh.safety.safetywalk;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Finds the route for a Safe Walk and scores how safe it is.
 *
 * The actual path geometry comes from whichever RouteEngine can produce
 * one: CppRouteEngine (real shortest-path routing over the campus
 * footpath/road graph) is tried first, and GeometricRouteEngine (a
 * straight line, with at most one detour waypoint around the single worst
 * hotspot) is the fallback when the C++ engine is unavailable. See both
 * classes' comments for why each exists and when each is used.
 *
 * Whichever engine wins, this class scores the result the same way: every
 * hotspot is checked against every segment of the returned polyline, not
 * just the two endpoints - a danger zone in the middle of a long walk
 * matters just as much as one right at the start or end, and this must
 * hold whether the polyline is a 2-point straight line or a 40-point real
 * path.
 */
@Service
public class SafeRouteService {

    // riskLevel -> how heavily it counts against safetyScore. Matches the
    // four levels docs/api-contract.md defines for a hotspot; the shared
    // definition lives in RiskWeights since GeometricRouteEngine and
    // CppRouteEngine both need it too.

    private final HotspotRepository hotspots;
    private final RouteEngine cppRouteEngine;
    private final RouteEngine geometricRouteEngine;

    // Explicit @Autowired, not left implicit: this class also has a
    // package-private constructor for tests (below), and Spring will not
    // reliably pick "the public one" on its own once more than one
    // constructor exists - without this it looks for a genuine no-arg
    // constructor instead and fails to start. Learned the hard way (a
    // production crash loop on IncidentAssignmentService, the same
    // two-constructor pattern), not assumed.
    @Autowired
    public SafeRouteService(
            HotspotRepository hotspots,
            CppRouteEngine cppRouteEngine,
            GeometricRouteEngine geometricRouteEngine) {
        this.hotspots = hotspots;
        this.cppRouteEngine = cppRouteEngine;
        this.geometricRouteEngine = geometricRouteEngine;
    }

    /** Used by tests to force a specific pair of engines without a Spring context. */
    SafeRouteService(HotspotRepository hotspots, RouteEngine cppRouteEngine, RouteEngine geometricRouteEngine) {
        this.hotspots = hotspots;
        this.cppRouteEngine = cppRouteEngine;
        this.geometricRouteEngine = geometricRouteEngine;
    }

    public SafeRouteResponse findSafeRoute(SafeRouteRequest request) {

        double fromLatitude = request.from().latitude();
        double fromLongitude = request.from().longitude();
        double toLatitude = request.to().latitude();
        double toLongitude = request.to().longitude();

        List<Hotspot> allHotspots = hotspots.findAll();

        List<SafeRouteResponse.RoutePoint> points = findPath(
                fromLatitude, fromLongitude, toLatitude, toLongitude, allHotspots);

        double distance = polylineLengthMetres(points);

        List<Hotspot> flagged = allHotspots.stream()
                .filter(hotspot -> minDistanceToPolylineMetres(hotspot, points) <= hotspot.getRadiusMetres())
                .toList();

        double safetyScore = computeSafetyScore(flagged, points);

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
     * Exactly the same point twice needs no engine at all - both would
     * otherwise still produce a correct (if slightly wasteful, for the C++
     * one) trivial answer, but short-circuiting here avoids spawning a
     * process for a walk that has not started yet.
     */
    private List<SafeRouteResponse.RoutePoint> findPath(
            double fromLatitude, double fromLongitude,
            double toLatitude, double toLongitude,
            List<Hotspot> allHotspots) {

        if (fromLatitude == toLatitude && fromLongitude == toLongitude) {
            SafeRouteResponse.RoutePoint point = new SafeRouteResponse.RoutePoint(fromLatitude, fromLongitude);
            return List.of(point, point);
        }

        // size() < 2, not just != null: a route with fewer than 2 points
        // (including Mockito's default "empty list" answer for an
        // unstubbed mock in tests) is not a usable route either.
        List<SafeRouteResponse.RoutePoint> fromCpp =
                tryEngine(cppRouteEngine, fromLatitude, fromLongitude, toLatitude, toLongitude, allHotspots);
        if (fromCpp != null && fromCpp.size() >= 2) {
            return fromCpp;
        }

        List<SafeRouteResponse.RoutePoint> fromGeometric =
                tryEngine(geometricRouteEngine, fromLatitude, fromLongitude, toLatitude, toLongitude, allHotspots);
        if (fromGeometric != null && fromGeometric.size() >= 2) {
            return fromGeometric;
        }

        // Both engines failed - genuinely should not happen, since the
        // geometric one never returns null or throws, but a straight line
        // beats a 500 response if it ever does.
        return List.of(
                new SafeRouteResponse.RoutePoint(fromLatitude, fromLongitude),
                new SafeRouteResponse.RoutePoint(toLatitude, toLongitude));
    }

    /**
     * A student asking for a route must never get a 500 because one
     * engine had a bug - any unexpected exception from either engine is
     * treated exactly like that engine returning null: try the next one.
     */
    private static List<SafeRouteResponse.RoutePoint> tryEngine(
            RouteEngine engine,
            double fromLatitude, double fromLongitude,
            double toLatitude, double toLongitude,
            List<Hotspot> hotspots) {
        try {
            return engine.findPath(fromLatitude, fromLongitude, toLatitude, toLongitude, hotspots);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double polylineLengthMetres(List<SafeRouteResponse.RoutePoint> points) {
        double total = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            SafeRouteResponse.RoutePoint a = points.get(i);
            SafeRouteResponse.RoutePoint b = points.get(i + 1);
            total += SafetyWalkService.distanceMetres(a.latitude(), a.longitude(), b.latitude(), b.longitude());
        }
        return total;
    }

    private static double minDistanceToPolylineMetres(Hotspot hotspot, List<SafeRouteResponse.RoutePoint> points) {
        double closest = Double.MAX_VALUE;
        for (int i = 0; i < points.size() - 1; i++) {
            SafeRouteResponse.RoutePoint a = points.get(i);
            SafeRouteResponse.RoutePoint b = points.get(i + 1);
            double distance = GeometricRouteEngine.distanceToSegmentMetres(
                    hotspot.getLatitude(), hotspot.getLongitude(),
                    a.latitude(), a.longitude(),
                    b.latitude(), b.longitude());
            closest = Math.min(closest, distance);
        }
        return closest;
    }

    /**
     * safetyScore starts at 1.0 (perfectly safe) and loses ground for every
     * flagged hotspot: riskWeight(hotspot) * howMuchItIntrudes, where
     * "intrudes" is the fraction of its own radius that actually overlaps
     * the closest segment of the path (0 = just barely touches the edge, 1
     * = the path goes straight through its centre). Penalties are summed
     * and capped so one severe hotspot cannot push the score below a floor
     * of 0.05 - a route is never reported as literally zero.
     */
    private static double computeSafetyScore(List<Hotspot> flagged, List<SafeRouteResponse.RoutePoint> points) {
        if (flagged.isEmpty()) {
            return 1.0;
        }

        double totalPenalty = 0.0;
        for (Hotspot hotspot : flagged) {
            double distanceToPath = minDistanceToPolylineMetres(hotspot, points);
            double intrusion = Math.max(
                    0.0,
                    (hotspot.getRadiusMetres() - distanceToPath) / hotspot.getRadiusMetres());
            totalPenalty += RiskWeights.of(hotspot.getRiskLevel()) * intrusion;
        }

        double score = 1.0 - Math.min(1.0, totalPenalty);
        return Math.max(0.05, score);
    }
}
