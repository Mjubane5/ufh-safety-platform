package za.ac.ufh.safety.safetywalk;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SafeRouteService's own job: pick which engine's polyline to use (real
 * CppRouteEngine routing preferred, GeometricRouteEngine as the fallback),
 * and score the result the same way regardless of which one won. Both
 * engines are mocked here - GeometricRouteEngineTest covers the fallback's
 * own geometry, and the C++ program itself is verified separately (see
 * algorithms/shortest_path/README.md) since it is not something a JVM unit
 * test can exercise.
 */
class SafeRouteServiceTest {

    private static final double FROM_LATITUDE = -32.78210;
    private static final double FROM_LONGITUDE = 26.84800;
    private static final double TO_LATITUDE = -32.78550;
    private static final double TO_LONGITUDE = 26.85200;

    private HotspotRepository hotspots;
    private RouteEngine cppRouteEngine;
    private RouteEngine geometricRouteEngine;
    private SafeRouteService service;

    @BeforeEach
    void setUp() {
        hotspots = mock(HotspotRepository.class);
        cppRouteEngine = mock(RouteEngine.class);
        geometricRouteEngine = mock(RouteEngine.class);
        service = new SafeRouteService(hotspots, cppRouteEngine, geometricRouteEngine);
        when(hotspots.findAll()).thenReturn(List.of());
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

    private SafeRouteRequest request(double fromLat, double fromLon, double toLat, double toLon) {
        return new SafeRouteRequest(
                new SafeRouteRequest.LocationPoint(fromLat, fromLon),
                new SafeRouteRequest.LocationPoint(toLat, toLon));
    }

    private static SafeRouteResponse.RoutePoint point(double lat, double lon) {
        return new SafeRouteResponse.RoutePoint(lat, lon);
    }

    // --- engine selection ----------------------------------------------

    @Test
    void usesTheCppEnginesRouteWhenItProducesOne() {
        List<SafeRouteResponse.RoutePoint> cppPath = List.of(
                point(FROM_LATITUDE, FROM_LONGITUDE),
                point(-32.78300, 26.84900),
                point(-32.78450, 26.85100),
                point(TO_LATITUDE, TO_LONGITUDE));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(cppPath);

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.points()).isEqualTo(cppPath);
        verify(geometricRouteEngine, never()).findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList());
    }

    @Test
    void fallsBackToTheGeometricEngineWhenTheCppEngineReturnsNull() {
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(null);
        List<SafeRouteResponse.RoutePoint> straightLine =
                List.of(point(FROM_LATITUDE, FROM_LONGITUDE), point(TO_LATITUDE, TO_LONGITUDE));
        when(geometricRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(straightLine);

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.points()).isEqualTo(straightLine);
    }

    @Test
    void fallsBackToTheGeometricEngineWhenTheCppEngineThrows() {
        // A student asking for a route must never get a 500 because the
        // subprocess call blew up for some reason - it is exactly like the
        // engine returning null.
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenThrow(new RuntimeException("process could not start"));
        List<SafeRouteResponse.RoutePoint> straightLine =
                List.of(point(FROM_LATITUDE, FROM_LONGITUDE), point(TO_LATITUDE, TO_LONGITUDE));
        when(geometricRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(straightLine);

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.points()).isEqualTo(straightLine);
    }

    @Test
    void aZeroLengthRouteNeverEvenAsksAnEngine() {
        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, FROM_LATITUDE, FROM_LONGITUDE));

        assertThat(response.distanceMetres()).isEqualTo(0.0);
        assertThat(response.safetyScore()).isEqualTo(1.0);
        verify(cppRouteEngine, never()).findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList());
        verify(geometricRouteEngine, never()).findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList());
    }

    // --- distance/estimatedSeconds are derived from the real polyline, ---
    // --- not a straight line, once a route has more than 2 points --------

    @Test
    void distanceIsTheSumOfEveryLegOfARealPathNotTheStraightLine() {
        SafeRouteResponse.RoutePoint bend = point(-32.78210, 26.85200); // a deliberate right-angle bend
        List<SafeRouteResponse.RoutePoint> bentPath = List.of(point(FROM_LATITUDE, FROM_LONGITUDE), bend, point(TO_LATITUDE, TO_LONGITUDE));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(bentPath);

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        double straightLineDistance = SafetyWalkService.distanceMetres(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE);
        double leg1 = SafetyWalkService.distanceMetres(FROM_LATITUDE, FROM_LONGITUDE, bend.latitude(), bend.longitude());
        double leg2 = SafetyWalkService.distanceMetres(bend.latitude(), bend.longitude(), TO_LATITUDE, TO_LONGITUDE);

        assertThat(response.distanceMetres()).isEqualTo(leg1 + leg2);
        assertThat(response.distanceMetres()).isGreaterThan(straightLineDistance);
    }

    @Test
    void estimatedSecondsIsDerivedFromDistanceAtAnOrdinaryWalkingPace() {
        List<SafeRouteResponse.RoutePoint> straightLine =
                List.of(point(FROM_LATITUDE, FROM_LONGITUDE), point(TO_LATITUDE, TO_LONGITUDE));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(straightLine);

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        int expectedSeconds = (int) Math.ceil(response.distanceMetres() / 1.33);
        assertThat(response.estimatedSeconds()).isEqualTo(expectedSeconds);
    }

    // --- safetyScore/avoidedHotspots, evaluated against every segment ----
    // --- of whichever polyline won, not just the endpoints ---------------

    @Test
    void aRouteWithNoNearbyHotspotsIsPerfectlySafe() {
        List<SafeRouteResponse.RoutePoint> straightLine =
                List.of(point(FROM_LATITUDE, FROM_LONGITUDE), point(TO_LATITUDE, TO_LONGITUDE));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(straightLine);

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.safetyScore()).isEqualTo(1.0);
        assertThat(response.avoidedHotspots()).isEmpty();
    }

    @Test
    void aHotspotNearTheMiddleSegmentOfARealPathIsFlaggedEvenThoughItIsFarFromBothEndpoints() {
        // A 3-leg path where the hotspot sits near the *middle* leg only -
        // this is exactly the shape a real road-following route takes, and
        // the old endpoint-only check this scoring replaced would have
        // missed it entirely.
        SafeRouteResponse.RoutePoint bend1 = point(-32.78300, 26.84900);
        SafeRouteResponse.RoutePoint bend2 = point(-32.78450, 26.85100);
        List<SafeRouteResponse.RoutePoint> path = List.of(
                point(FROM_LATITUDE, FROM_LONGITUDE), bend1, bend2, point(TO_LATITUDE, TO_LONGITUDE));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(path);

        // Centred on the bend1->bend2 leg's midpoint.
        Hotspot midLegHotspot = hotspot(7L, -32.78375, 26.85000, 60.0, "elevated");
        when(hotspots.findAll()).thenReturn(List.of(midLegHotspot));

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.avoidedHotspots()).containsExactly(7L);
        assertThat(response.safetyScore()).isLessThan(1.0);
    }

    @Test
    void aHotspotWellOffToTheSideOfEveryLegIsNotFlagged() {
        List<SafeRouteResponse.RoutePoint> straightLine =
                List.of(point(FROM_LATITUDE, FROM_LONGITUDE), point(TO_LATITUDE, TO_LONGITUDE));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(straightLine);

        Hotspot farHotspot = hotspot(9L, -32.70000, 26.90000, 100.0, "high");
        when(hotspots.findAll()).thenReturn(List.of(farHotspot));

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.avoidedHotspots()).isEmpty();
        assertThat(response.safetyScore()).isEqualTo(1.0);
    }

    @Test
    void allHotspotsArePassedToTheCppEngineSoItCanRouteAroundThemItself() {
        Hotspot hotspot = hotspot(4L, -32.78380, 26.85000, 60.0, "high");
        when(hotspots.findAll()).thenReturn(List.of(hotspot));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(List.of(point(FROM_LATITUDE, FROM_LONGITUDE), point(TO_LATITUDE, TO_LONGITUDE)));

        service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        verify(cppRouteEngine).findPath(
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.eq(List.of(hotspot)));
    }

    @Test
    void aHighRiskHotspotHurtsTheScoreMoreThanALowRiskOneAtTheSameIntrusion() {
        List<SafeRouteResponse.RoutePoint> straightLine =
                List.of(point(FROM_LATITUDE, FROM_LONGITUDE), point(TO_LATITUDE, TO_LONGITUDE));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(straightLine);

        Hotspot highRisk = hotspot(1L, -32.78380, 26.85000, 200.0, "high");
        Hotspot lowRisk = hotspot(2L, -32.78380, 26.85000, 200.0, "low");

        when(hotspots.findAll()).thenReturn(List.of(highRisk));
        double highRiskScore = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE)).safetyScore();

        when(hotspots.findAll()).thenReturn(List.of(lowRisk));
        double lowRiskScore = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE)).safetyScore();

        assertThat(highRiskScore).isLessThan(lowRiskScore);
    }

    @Test
    void theScoreNeverDropsToZeroNoMatterHowSevereTheHotspot() {
        List<SafeRouteResponse.RoutePoint> straightLine =
                List.of(point(FROM_LATITUDE, FROM_LONGITUDE), point(TO_LATITUDE, TO_LONGITUDE));
        when(cppRouteEngine.findPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyList()))
                .thenReturn(straightLine);

        Hotspot severe = hotspot(4L, -32.78380, 26.85000, 5000.0, "high");
        when(hotspots.findAll()).thenReturn(List.of(severe));

        SafeRouteResponse response = service.findSafeRoute(request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.safetyScore()).isGreaterThanOrEqualTo(0.05);
    }
}
