package za.ac.ufh.safety.safetywalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real shortest-path routing over the campus footpath/road graph, via the
 * compiled algorithms/shortest_path C++ program - see that program's own
 * header comment for the algorithm (Dijkstra with a per-edge risk penalty
 * for hotspots) and the graph data's origin (real OpenStreetMap footway/
 * path/residential/service ways around campus, not synthetic waypoints).
 *
 * app.routing.cpp-binary / app.routing.graph-file are unset by default, so
 * a teammate with no C++ toolchain sees findPath() return null every time
 * and SafeRouteService silently falls back to GeometricRouteEngine - the
 * same "optional, never blocks the team" pattern app.mail.api-key already
 * uses for SendGrid. The Docker build compiles the binary and ships the
 * graph file alongside app.jar, and sets both properties, so production
 * actually gets the real routing.
 *
 * A subprocess per Safe Walk route request is a deliberate departure from
 * the original stack-table wording ("C++ - standalone module, not in the
 * request path"): a precomputed-offline approach cannot serve an arbitrary
 * student-clicked destination, only a fixed set of pairs chosen in advance.
 * The graph itself (a few thousand nodes) makes one Dijkstra run take low
 * single-digit milliseconds, so the per-request cost is negligible - the
 * timeout below is there for the process actually failing to start or
 * hanging, not for ordinary running time.
 */
@Component
public class CppRouteEngine implements RouteEngine {

    private static final Logger log = LoggerFactory.getLogger(CppRouteEngine.class);
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(3);

    private final String binaryPath;
    private final String graphPath;
    private final ObjectMapper objectMapper;

    CppRouteEngine(
            @Value("${app.routing.cpp-binary:}") String binaryPath,
            @Value("${app.routing.graph-file:}") String graphPath,
            ObjectMapper objectMapper
    ) {
        this.binaryPath = binaryPath;
        this.graphPath = graphPath;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SafeRouteResponse.RoutePoint> findPath(
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude,
            List<Hotspot> hotspots) {

        if (binaryPath.isBlank() || graphPath.isBlank()) {
            return null;
        }
        if (!Files.isRegularFile(Path.of(binaryPath)) || !Files.isRegularFile(Path.of(graphPath))) {
            log.warn("app.routing.cpp-binary/graph-file are set but do not point to real files - "
                    + "falling back to the geometric route. binary={} graph={}", binaryPath, graphPath);
            return null;
        }

        List<String> command = new ArrayList<>();
        command.add(binaryPath);
        command.add(graphPath);
        command.add(String.valueOf(fromLatitude));
        command.add(String.valueOf(fromLongitude));
        command.add(String.valueOf(toLatitude));
        command.add(String.valueOf(toLongitude));
        for (Hotspot hotspot : hotspots) {
            command.add(String.valueOf(hotspot.getLatitude()));
            command.add(String.valueOf(hotspot.getLongitude()));
            command.add(String.valueOf(hotspot.getRadiusMetres()));
            command.add(String.valueOf(RiskWeights.of(hotspot.getRiskLevel())));
        }

        Process process = null;
        try {
            process = new ProcessBuilder(command).start();

            boolean finished = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                log.warn("shortest_path did not finish within {} - falling back to the geometric route", PROCESS_TIMEOUT);
                return null;
            }

            String stdout = readAll(process.getInputStream());
            if (process.exitValue() != 0) {
                log.warn("shortest_path exited {} - falling back to the geometric route. Output: {}",
                        process.exitValue(), stdout);
                return null;
            }

            return parsePoints(stdout);
        } catch (Exception e) {
            // Whatever went wrong - the binary is not executable, the OS
            // refused to spawn it, the output did not parse - this engine
            // is simply unavailable right now. SafeRouteService's fallback
            // means a broken or missing C++ build never blocks a student
            // from getting a route, only makes it the less precise one.
            log.warn("Could not run the shortest_path routing engine - falling back to the geometric route", e);
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<SafeRouteResponse.RoutePoint> parsePoints(String stdout) throws IOException {
        JsonNode json = objectMapper.readTree(stdout);
        if (!json.path("ok").asBoolean(false)) {
            log.warn("shortest_path reported an error - falling back to the geometric route: {}",
                    json.path("error").asText("(no message)"));
            return null;
        }

        List<SafeRouteResponse.RoutePoint> points = new ArrayList<>();
        for (JsonNode point : json.path("points")) {
            points.add(new SafeRouteResponse.RoutePoint(
                    point.path("latitude").asDouble(),
                    point.path("longitude").asDouble()));
        }
        return points.size() >= 2 ? points : null;
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
