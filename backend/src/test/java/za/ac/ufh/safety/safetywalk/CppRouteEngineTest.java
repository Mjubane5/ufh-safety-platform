package za.ac.ufh.safety.safetywalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CppRouteEngine's one hard rule: it must return null, never throw, in
 * every case where the compiled binary or graph file is not genuinely
 * usable - SafeRouteService relies on that to fall back safely. Actually
 * running the compiled binary is exercised manually (see
 * algorithms/shortest_path/README.md), not here - a JVM unit test has no
 * business shelling out to a native process.
 */
class CppRouteEngineTest {

    private static final double FROM_LATITUDE = -32.78210;
    private static final double FROM_LONGITUDE = 26.84800;
    private static final double TO_LATITUDE = -32.78550;
    private static final double TO_LONGITUDE = 26.85200;

    @Test
    void returnsNullWhenNeitherPropertyIsConfigured() {
        CppRouteEngine engine = new CppRouteEngine("", "", new ObjectMapper());

        List<SafeRouteResponse.RoutePoint> result =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE, List.of());

        assertThat(result).isNull();
    }

    @Test
    void returnsNullWhenTheBinaryPathDoesNotExist() {
        CppRouteEngine engine = new CppRouteEngine(
                "/does/not/exist/shortest_path", "/does/not/exist/campus_graph.txt", new ObjectMapper());

        List<SafeRouteResponse.RoutePoint> result =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE, List.of());

        assertThat(result).isNull();
    }

    @Test
    void returnsNullWhenOnlyTheGraphFileIsConfigured() {
        // Half-configured (one property set, the other blank) must fail
        // safe exactly like fully unconfigured - not attempt to run a
        // command with a blank executable path.
        CppRouteEngine engine = new CppRouteEngine("", "/some/graph.txt", new ObjectMapper());

        List<SafeRouteResponse.RoutePoint> result =
                engine.findPath(FROM_LATITUDE, FROM_LONGITUDE, TO_LATITUDE, TO_LONGITUDE, List.of());

        assertThat(result).isNull();
    }
}
