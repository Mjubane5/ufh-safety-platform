// Real shortest-path routing over the campus footpath/road graph.
//
// This replaces the straight-line-plus-one-detour approximation
// SafeRouteService used before this module existed - a Safe Walk route was
// a literal straight line between two GPS points, so it cut across
// buildings and open ground instead of following an actual path. See
// NearestResponderSelector's long-standing comment: "The real shortest-path
// work over the campus footpath graph is the C++ module." - that promise,
// unfulfilled until now.
//
// Graph data (data/campus_graph.txt) is built from real OpenStreetMap
// footway/path/residential/service way data around the UFH campus - see
// data/README.md for how it was generated and how to regenerate it - not
// synthetic waypoints, so the output follows real, walkable ground.
//
// Usage:
//   shortest_path <graph_file> <fromLat> <fromLon> <toLat> <toLon> \
//       [hotLat hotLon hotRadiusMetres hotWeight]...
//
// Each optional trailing group of four numbers is a hotspot to route
// around: its centre, radius in metres, and a risk weight in the same
// 0..1 range docs/api-contract.md defines for a hotspot's riskLevel
// (low=0.15 ... high=0.9). An edge whose midpoint falls inside a hotspot's
// radius gets its cost multiplied up in proportion to how deep inside it
// is, so Dijkstra naturally prefers a longer physical path over a shorter
// one that walks through the danger zone - the same trade-off a person
// making the same walk would make, not just a score computed afterwards.
//
// Output (stdout), one JSON object, nothing else:
//   Success: {"ok":true,"distanceMetres":123.4,"points":[{"latitude":...,"longitude":...}, ...]}
//   Failure: {"ok":false,"error":"..."}  (also a non-zero exit code)
//
// The Java side (za.ac.ufh.safety.safetywalk.CppRouteEngine) treats any
// non-zero exit, a process that does not finish quickly, or output that
// does not parse as "the engine is unavailable right now" and falls back
// to the older straight-line/detour geometry rather than failing the
// request - a Safe Walk must never be blocked by this process being
// missing, slow, or broken.

#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <limits>
#include <queue>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr double kEarthRadiusMetres = 6371000.0;

double toRadians(double degrees) { return degrees * M_PI / 180.0; }

// Great-circle distance between two lat/lon points. Good enough at campus
// scale (a few kilometres at most) - the same reasoning already documented
// on NearestResponderSelector applies here.
double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
    double la1 = toRadians(lat1);
    double la2 = toRadians(lat2);
    double dLat = toRadians(lat2 - lat1);
    double dLon = toRadians(lon2 - lon1);
    double a = std::sin(dLat / 2) * std::sin(dLat / 2) +
               std::cos(la1) * std::cos(la2) * std::sin(dLon / 2) * std::sin(dLon / 2);
    return kEarthRadiusMetres * 2.0 * std::atan2(std::sqrt(a), std::sqrt(1.0 - a));
}

struct Edge {
    int to;
    double weightMetres;  // real physical length of this path segment
};

struct Hotspot {
    double lat;
    double lon;
    double radiusMetres;
    double weight;
};

struct Graph {
    std::vector<double> lat;
    std::vector<double> lon;
    std::vector<std::vector<Edge>> adjacency;

    int nodeCount() const { return static_cast<int>(lat.size()); }

    // Linear scan is fine here: a single campus graph is a few thousand
    // nodes, so this is well under a millisecond - not worth the added
    // complexity of a spatial index for this scale.
    int nearestNode(double queryLat, double queryLon) const {
        int best = -1;
        double bestDistance = std::numeric_limits<double>::max();
        for (int i = 0; i < nodeCount(); ++i) {
            double d = haversineMetres(queryLat, queryLon, lat[i], lon[i]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
};

// File format (see data/README.md):
//   line 1: "<nodeCount> <edgeCount>"
//   next nodeCount lines: "<lat> <lon>" (node id = 0-based line order)
//   next edgeCount lines: "<fromNodeId> <toNodeId> <weightMetres>" (directed;
//     the generator already emits both directions for a walkable way)
bool loadGraph(const std::string& path, Graph& graph) {
    std::ifstream in(path);
    if (!in.is_open()) return false;

    int nodeCount = 0;
    int edgeCount = 0;
    in >> nodeCount >> edgeCount;
    if (!in || nodeCount <= 0) return false;

    graph.lat.resize(nodeCount);
    graph.lon.resize(nodeCount);
    graph.adjacency.assign(nodeCount, {});

    for (int i = 0; i < nodeCount; ++i) {
        in >> graph.lat[i] >> graph.lon[i];
        if (!in) return false;
    }
    for (int i = 0; i < edgeCount; ++i) {
        int u = -1;
        int v = -1;
        double w = 0.0;
        in >> u >> v >> w;
        if (!in) return false;
        if (u < 0 || u >= nodeCount || v < 0 || v >= nodeCount || w < 0) continue;
        graph.adjacency[u].push_back({v, w});
    }
    return true;
}

// Extra cost, as a multiplier on top of the edge's real length, for an edge
// whose midpoint falls inside a hotspot's radius. Several overlapping
// hotspots add up rather than replacing each other, matching
// SafeRouteService's own scoring before this module existed.
double edgeRiskPenalty(double midLat, double midLon, const std::vector<Hotspot>& hotspots) {
    double totalWeight = 0.0;
    for (const Hotspot& h : hotspots) {
        if (h.radiusMetres <= 0) continue;
        double d = haversineMetres(midLat, midLon, h.lat, h.lon);
        if (d <= h.radiusMetres) {
            double intrusion = (h.radiusMetres - d) / h.radiusMetres;  // 0..1
            totalWeight += h.weight * intrusion;
        }
    }
    return totalWeight;
}

// Textbook Dijkstra with a binary heap (std::priority_queue). The graph is
// small enough (a few thousand nodes/edges for one campus) that there is no
// need for A* or a more elaborate structure to keep this fast - this is a
// deliberate, documented choice, not an oversight.
bool dijkstra(const Graph& graph, int source, int target, const std::vector<Hotspot>& hotspots,
              std::vector<int>& outPathNodes, double& outPhysicalDistanceMetres) {
    int n = graph.nodeCount();
    std::vector<double> bestCost(n, std::numeric_limits<double>::infinity());
    std::vector<double> physicalDistance(n, 0.0);
    std::vector<int> previous(n, -1);
    std::vector<bool> settled(n, false);

    using QueueItem = std::pair<double, int>;  // (cost so far, node)
    std::priority_queue<QueueItem, std::vector<QueueItem>, std::greater<>> frontier;

    bestCost[source] = 0.0;
    frontier.push({0.0, source});

    while (!frontier.empty()) {
        auto [cost, u] = frontier.top();
        frontier.pop();
        if (settled[u]) continue;
        settled[u] = true;
        if (u == target) break;

        for (const Edge& edge : graph.adjacency[u]) {
            if (settled[edge.to]) continue;
            double midLat = (graph.lat[u] + graph.lat[edge.to]) / 2.0;
            double midLon = (graph.lon[u] + graph.lon[edge.to]) / 2.0;
            double penalty = edgeRiskPenalty(midLat, midLon, hotspots);
            double edgeCost = edge.weightMetres * (1.0 + penalty);
            double candidate = cost + edgeCost;
            if (candidate < bestCost[edge.to]) {
                bestCost[edge.to] = candidate;
                physicalDistance[edge.to] = physicalDistance[u] + edge.weightMetres;
                previous[edge.to] = u;
                frontier.push({candidate, edge.to});
            }
        }
    }

    if (!settled[target]) return false;

    std::vector<int> path;
    for (int at = target; at != -1; at = previous[at]) {
        path.push_back(at);
        if (at == source) break;
    }
    if (path.empty() || path.back() != source) return false;
    std::reverse(path.begin(), path.end());

    outPathNodes = path;
    outPhysicalDistanceMetres = physicalDistance[target];
    return true;
}

std::string escapeJson(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        if (c == '"' || c == '\\') out += '\\';
        out += c;
    }
    return out;
}

void printError(const std::string& message) {
    std::cout << "{\"ok\":false,\"error\":\"" << escapeJson(message) << "\"}" << std::endl;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 6) {
        printError(
            "usage: shortest_path <graph_file> <fromLat> <fromLon> <toLat> <toLon> "
            "[hotLat hotLon hotRadiusMetres hotWeight]...");
        return 1;
    }

    std::string graphPath = argv[1];
    double fromLat, fromLon, toLat, toLon;
    try {
        fromLat = std::stod(argv[2]);
        fromLon = std::stod(argv[3]);
        toLat = std::stod(argv[4]);
        toLon = std::stod(argv[5]);
    } catch (const std::exception&) {
        printError("from/to coordinates must be numbers");
        return 1;
    }

    std::vector<Hotspot> hotspots;
    for (int i = 6; i + 3 < argc; i += 4) {
        try {
            hotspots.push_back({std::stod(argv[i]), std::stod(argv[i + 1]), std::stod(argv[i + 2]),
                                 std::stod(argv[i + 3])});
        } catch (const std::exception&) {
            printError("hotspot arguments must be numbers");
            return 1;
        }
    }

    Graph graph;
    if (!loadGraph(graphPath, graph)) {
        printError("could not load graph file: " + graphPath);
        return 1;
    }

    int source = graph.nearestNode(fromLat, fromLon);
    int target = graph.nearestNode(toLat, toLon);
    if (source < 0 || target < 0) {
        printError("graph has no nodes");
        return 1;
    }

    std::vector<int> pathNodes;
    double onGraphDistanceMetres = 0.0;
    if (!dijkstra(graph, source, target, hotspots, pathNodes, onGraphDistanceMetres)) {
        printError("no path found between the two points");
        return 1;
    }

    // Total distance includes the short "snap" segments from the exact
    // requested points to the nearest graph node on each end, not just the
    // on-graph portion - otherwise a short walk near the edge of the graph
    // would under-report its own length.
    double snapInMetres = haversineMetres(fromLat, fromLon, graph.lat[source], graph.lon[source]);
    double snapOutMetres = haversineMetres(graph.lat[target], graph.lon[target], toLat, toLon);
    double totalDistanceMetres = snapInMetres + onGraphDistanceMetres + snapOutMetres;

    std::ostringstream out;
    out.precision(7);
    out << std::fixed;
    out << "{\"ok\":true,\"distanceMetres\":" << totalDistanceMetres << ",\"points\":[";
    out << "{\"latitude\":" << fromLat << ",\"longitude\":" << fromLon << "}";
    for (int nodeId : pathNodes) {
        out << ",{\"latitude\":" << graph.lat[nodeId] << ",\"longitude\":" << graph.lon[nodeId] << "}";
    }
    out << ",{\"latitude\":" << toLat << ",\"longitude\":" << toLon << "}";
    out << "]}";

    std::cout << out.str() << std::endl;
    return 0;
}
