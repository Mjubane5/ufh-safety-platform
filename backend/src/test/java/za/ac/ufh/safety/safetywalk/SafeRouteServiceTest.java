package za.ac.ufh.safety.safetywalk;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafeRouteServiceTest {

    // A straight walk of a few hundred metres across campus, used as the
    // from/to pair for most tests below.
    private static final double FROM_LATITUDE = -32.78210;
    private static final double FROM_LONGITUDE = 26.84800;
    private static final double TO_LATITUDE = -32.78550;
    private static final double TO_LONGITUDE = 26.85200;

    private HotspotRepository hotspots;
    private SafeRouteService service;

    @BeforeEach
    void setUp() {
        hotspots = mock(HotspotRepository.class);
        service = new SafeRouteService(hotspots);
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

    // --- no hotspots on the way -------------------------------------------

    @Test
    void aRouteWithNoNearbyHotspotsIsPerfectlySafe() {
        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.safetyScore()).isEqualTo(1.0);
        assertThat(response.avoidedHotspots()).isEmpty();
    }

    @Test
    void withNothingFlaggedThePointsAreJustTheDirectLine() {
        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.points()).extracting(SafeRouteResponse.RoutePoint::latitude,
                        SafeRouteResponse.RoutePoint::longitude)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(FROM_LATITUDE, FROM_LONGITUDE),
                        org.assertj.core.groups.Tuple.tuple(TO_LATITUDE, TO_LONGITUDE));
    }

    // --- a hotspot in the middle of the walk, not near either end ---------
    //
    // This is the case the old implementation missed entirely - it only
    // checked distance from the two endpoints, so a danger zone sitting
    // squarely between them was invisible.

    @Test
    void aHotspotBetweenTheEndpointsIsFlaggedEvenThoughItIsFarFromBoth() {
        // Roughly the midpoint of the FROM/TO pair above, nowhere near
        // either endpoint on its own.
        Hotspot midpointHotspot = hotspot(7L, -32.78380, 26.85000, 60.0, "elevated");
        when(hotspots.findAll()).thenReturn(List.of(midpointHotspot));

        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.avoidedHotspots()).containsExactly(7L);
        assertThat(response.safetyScore()).isLessThan(1.0);
    }

    @Test
    void aHotspotWellOffToTheSideOfTheWalkIsNotFlagged() {
        // A couple of kilometres away from the direct line - nowhere near it.
        Hotspot farHotspot = hotspot(9L, -32.70000, 26.90000, 100.0, "high");
        when(hotspots.findAll()).thenReturn(List.of(farHotspot));

        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.avoidedHotspots()).isEmpty();
        assertThat(response.safetyScore()).isEqualTo(1.0);
    }

    @Test
    void aHotspotRightAtTheStartingPointIsStillFlagged() {
        // Endpoint coverage must not regress now that the check is
        // segment-based rather than endpoint-based.
        Hotspot atStart = hotspot(3L, FROM_LATITUDE, FROM_LONGITUDE, 40.0, "moderate");
        when(hotspots.findAll()).thenReturn(List.of(atStart));

        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.avoidedHotspots()).containsExactly(3L);
    }

    // --- safetyScore weighting ---------------------------------------------

    @Test
    void aHighRiskHotspotHurtsTheScoreMoreThanALowRiskOneAtTheSameIntrusion() {
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
        // A huge, high-risk hotspot centred right on the direct line.
        Hotspot severe = hotspot(4L, -32.78380, 26.85000, 5000.0, "high");
        when(hotspots.findAll()).thenReturn(List.of(severe));

        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.safetyScore()).isGreaterThanOrEqualTo(0.05);
    }

    // --- detour waypoint -----------------------------------------------

    @Test
    void aFlaggedHotspotAddsADetourWaypointBetweenTheEndpoints() {
        Hotspot midpointHotspot = hotspot(7L, -32.78380, 26.85000, 60.0, "elevated");
        when(hotspots.findAll()).thenReturn(List.of(midpointHotspot));

        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        assertThat(response.points()).hasSize(3);
        assertThat(response.points().get(0).latitude()).isEqualTo(FROM_LATITUDE);
        assertThat(response.points().get(2).latitude()).isEqualTo(TO_LATITUDE);
    }

    @Test
    void theDetourWaypointClearsTheHotspotsRadiusWithMargin() {
        Hotspot midpointHotspot = hotspot(7L, -32.78380, 26.85000, 60.0, "elevated");
        when(hotspots.findAll()).thenReturn(List.of(midpointHotspot));

        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        SafeRouteResponse.RoutePoint detour = response.points().get(1);
        double distanceFromHotspot = SafetyWalkService.distanceMetres(
                detour.latitude(), detour.longitude(),
                midpointHotspot.getLatitude(), midpointHotspot.getLongitude());

        assertThat(distanceFromHotspot).isGreaterThanOrEqualTo(midpointHotspot.getRadiusMetres());
    }

    // --- wiring sanity: distance/estimatedSeconds pass through unchanged ---

    @Test
    void estimatedSecondsIsDerivedFromDistanceAtAnOrdinaryWalkingPace() {
        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE));

        int expectedSeconds = (int) Math.ceil(response.distanceMetres() / 1.33);
        assertThat(response.estimatedSeconds()).isEqualTo(expectedSeconds);
    }

    @Test
    void aZeroLengthRouteDoesNotBlowUp() {
        SafeRouteResponse response = service.findSafeRoute(
                request(FROM_LATITUDE, FROM_LONGITUDE, FROM_LATITUDE, FROM_LONGITUDE));

        assertThat(response.distanceMetres()).isEqualTo(0.0);
        assertThat(response.safetyScore()).isEqualTo(1.0);
    }
}
