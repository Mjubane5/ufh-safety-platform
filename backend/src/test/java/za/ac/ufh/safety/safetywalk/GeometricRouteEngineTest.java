package za.ac.ufh.safety.safetywalk;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The specification for the straight-line-plus-one-detour fallback -
 * SafeRouteService reaches for this only when CppRouteEngine is
 * unavailable, but it must still behave correctly on its own; see
 * SafeRouteServiceTest for the orchestration/fallback logic itself.
 */
class GeometricRouteEngineTest {

    // A straight walk of a few hundred metres across campus, used as the
    // from/to pair for most tests below.
    private static final double FROM_LATITUDE = -32.78210;
    private static final double FROM_LONGITUDE = 26.84800;
    private static final double TO_LATITUDE = -32.78550;
    private static final double TO_LONGITUDE = 26.85200;

    private GeometricRouteEngine engine;

    @BeforeEach
    void setUp() {
        engine = new GeometricRouteEngine();
    }

    private Hotspot hotspot(long id, double latitude, double longitude, double radiusMetres, String riskLevel) {
        Hotspot hotspot = new Hotspot();
        ReflectionTestUtils.setField(hotspot, "hotspotId", id);
        hotspot.setName("Test hotspot " + id);
        hotspot.setLatitude(latitude);
        hotspot.setLongitude(longitude);
        hotspot.setRadiusMetres(radiusMetres);
        hotspot.setRiskLevel(riskLevel);
        hotspot.setIncidentCount(0);
        hotspot.setComputedAt(Instant.now());
        return hotspot;
    }

    @Test
    void withNothingFlaggedThePointsAreJustTheDirectLine() {
        List<SafeRouteResponse.RoutePoint> points =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE, List.of());

        assertThat(points).extracting(SafeRouteResponse.RoutePoint::latitude, SafeRouteResponse.RoutePoint::longitude)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(FROM_LATITUDE, FROM_LONGITUDE),
                        org.assertj.core.groups.Tuple.tuple(TO_LATITUDE, TO_LONGITUDE));
    }

    // --- a hotspot in the middle of the walk, not near either end ---------
    //
    // The old (pre-C++) implementation this fallback is descended from only
    // checked distance from the two endpoints, so a danger zone sitting
    // squarely between them was invisible - this must not regress.

    @Test
    void aHotspotBetweenTheEndpointsAddsADetourWaypoint() {
        Hotspot midpointHotspot = hotspot(7L, -32.78380, 26.85000, 60.0, "elevated");

        List<SafeRouteResponse.RoutePoint> points =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE, List.of(midpointHotspot));

        assertThat(points).hasSize(3);
        assertThat(points.get(0).latitude()).isEqualTo(FROM_LATITUDE);
        assertThat(points.get(2).latitude()).isEqualTo(TO_LATITUDE);
    }

    @Test
    void aHotspotWellOffToTheSideOfTheWalkIsIgnored() {
        // A couple of kilometres away from the direct line - nowhere near it.
        Hotspot farHotspot = hotspot(9L, -32.70000, 26.90000, 100.0, "high");

        List<SafeRouteResponse.RoutePoint> points =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE, List.of(farHotspot));

        assertThat(points).hasSize(2);
    }

    @Test
    void theDetourWaypointClearsTheHotspotsRadiusWithMargin() {
        Hotspot midpointHotspot = hotspot(7L, -32.78380, 26.85000, 60.0, "elevated");

        List<SafeRouteResponse.RoutePoint> points =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE, List.of(midpointHotspot));

        SafeRouteResponse.RoutePoint detour = points.get(1);
        double distanceFromHotspot = SafetyWalkService.distanceMetres(
                detour.latitude(), detour.longitude(),
                midpointHotspot.getLatitude(), midpointHotspot.getLongitude());

        assertThat(distanceFromHotspot).isGreaterThanOrEqualTo(midpointHotspot.getRadiusMetres());
    }

    @Test
    void withSeveralFlaggedHotspotsOnlyTheMostSignificantGetsADetour() {
        // Both intrude on the direct line; the nearer/larger one (in terms
        // of how deep it cuts into the path) should be the one detoured
        // around - documented as a known simplification (see the class
        // comment), not a bug, so this pins down which one wins rather than
        // leaving it unspecified.
        Hotspot minor = hotspot(1L, -32.78380, 26.85000, 20.0, "low");
        Hotspot major = hotspot(2L, -32.78380, 26.85000, 90.0, "high");

        List<SafeRouteResponse.RoutePoint> points =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE, List.of(minor, major));

        assertThat(points).hasSize(3);
        SafeRouteResponse.RoutePoint detour = points.get(1);
        double distanceFromMajor = SafetyWalkService.distanceMetres(
                detour.latitude(), detour.longitude(), major.getLatitude(), major.getLongitude());
        assertThat(distanceFromMajor).isGreaterThanOrEqualTo(major.getRadiusMetres());
    }

    @Test
    void aZeroLengthRouteDoesNotBlowUp() {
        List<SafeRouteResponse.RoutePoint> points =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, FROM_LATITUDE, FROM_LONGITUDE, List.of());

        assertThat(points).hasSize(2);
        assertThat(points.get(0).latitude()).isEqualTo(FROM_LATITUDE);
        assertThat(points.get(1).latitude()).isEqualTo(FROM_LATITUDE);
    }

    @Test
    void aZeroLengthRouteWithAHotspotRightOnItDoesNotBlowUp() {
        // segmentLengthSquared is 0 here, which is the edge case
        // detourAround's own fallback (push perpendicular to a
        // zero-length "direction of travel") exists for.
        Hotspot atThePoint = hotspot(3L, FROM_LATITUDE, FROM_LONGITUDE, 40.0, "moderate");

        List<SafeRouteResponse.RoutePoint> points =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, FROM_LATITUDE, FROM_LONGITUDE, List.of(atThePoint));

        assertThat(points).hasSize(3);
    }

    // --- distanceToSegmentMetres, used directly by SafeRouteService's ------
    // --- scoring, not just internally by this engine ------------------------

    @Test
    void distanceToSegmentMetresMatchesEndpointDistanceWhenClampedAtEitherEnd() {
        double distanceFromStart = GeometricRouteEngine.distanceToSegmentMetres(
                FROM_LATITUDE, FROM_LONGITUDE, FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE);
        assertThat(distanceFromStart).isZero();

        double distanceFromEnd = GeometricRouteEngine.distanceToSegmentMetres(
                TO_LATITUDE, TO_LONGITUDE, FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE);
        assertThat(distanceFromEnd).isZero();
    }
}
