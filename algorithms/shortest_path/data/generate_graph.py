#!/usr/bin/env python3
"""Regenerates campus_graph.txt from real OpenStreetMap data.

Queries the Overpass API for every footway/path/pedestrian/service/
residential/... way within 1200m of the campus centre used throughout the
frontend (CAMPUS_CENTER in frontend/js/map.js), and writes a compact graph
file shortest_path.cpp can load directly - see that file's loadGraph()
comment for the exact format.

Usage:
    python3 generate_graph.py [output_path]

No third-party dependencies - just the standard library, so nobody needs to
pip install anything to regenerate the graph.
"""
import json
import math
import sys
import urllib.request
from collections import defaultdict, deque

CAMPUS_LATITUDE = -32.7833
CAMPUS_LONGITUDE = 26.8497
RADIUS_METRES = 1200

HIGHWAY_TYPES = (
    "footway", "path", "pedestrian", "service", "residential",
    "living_street", "track", "primary", "secondary", "tertiary",
    "unclassified", "steps",
)

OVERPASS_URL = "https://overpass-api.de/api/interpreter"


def fetch_osm_data():
    highway_filter = "|".join(HIGHWAY_TYPES)
    query = f"""
[out:json][timeout:60];
(
  way["highway"~"^({highway_filter})$"]
     (around:{RADIUS_METRES},{CAMPUS_LATITUDE},{CAMPUS_LONGITUDE});
);
(._;>;);
out body;
"""
    data = ("data=" + query).encode("utf-8")
    request = urllib.request.Request(OVERPASS_URL, data=data, headers={"User-Agent": "ufh-safety-platform"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def haversine_metres(a, b):
    lat1, lon1 = a
    lat2, lon2 = b
    earth_radius = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    x = math.sin(d_phi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(d_lambda / 2) ** 2
    return 2 * earth_radius * math.asin(math.sqrt(x))


def build_graph(osm_data):
    nodes = {}
    ways = []
    for element in osm_data["elements"]:
        if element["type"] == "node":
            nodes[element["id"]] = (element["lat"], element["lon"])
        elif element["type"] == "way":
            ways.append(element)

    adjacency = defaultdict(dict)
    for way in ways:
        way_nodes = way.get("nodes", [])
        for i in range(len(way_nodes) - 1):
            a, b = way_nodes[i], way_nodes[i + 1]
            if a not in nodes or b not in nodes:
                continue
            distance = haversine_metres(nodes[a], nodes[b])
            if distance <= 0:
                continue
            # Keep the shorter edge if OSM ever duplicates one.
            if b not in adjacency[a] or adjacency[a][b] > distance:
                adjacency[a][b] = distance
            if a not in adjacency[b] or adjacency[b][a] > distance:
                adjacency[b][a] = distance

    return nodes, adjacency


def largest_connected_component(adjacency):
    """Keeps the graph routable end to end - an isolated fragment a few
    ways wide, disconnected from the main network, would make some
    requests fail to find any path at all for no reason a student could
    understand."""
    visited = set()
    components = []
    for start in adjacency:
        if start in visited:
            continue
        component = set()
        queue = deque([start])
        visited.add(start)
        while queue:
            current = queue.popleft()
            component.add(current)
            for neighbour in adjacency[current]:
                if neighbour not in visited:
                    visited.add(neighbour)
                    queue.append(neighbour)
        components.append(component)
    return max(components, key=len)


def write_graph_file(path, nodes, adjacency, keep_ids):
    sorted_ids = sorted(keep_ids)
    remap = {old_id: index for index, old_id in enumerate(sorted_ids)}
    edge_count = sum(len(adjacency[old_id]) for old_id in sorted_ids)

    with open(path, "w", encoding="utf-8") as f:
        f.write(f"{len(sorted_ids)} {edge_count}\n")
        for old_id in sorted_ids:
            lat, lon = nodes[old_id]
            f.write(f"{lat:.7f} {lon:.7f}\n")
        for old_id in sorted_ids:
            u = remap[old_id]
            for neighbour_id, weight in adjacency[old_id].items():
                v = remap[neighbour_id]
                f.write(f"{u} {v} {weight:.3f}\n")


def main():
    output_path = sys.argv[1] if len(sys.argv) > 1 else "campus_graph.txt"

    print(f"Fetching OSM ways within {RADIUS_METRES}m of ({CAMPUS_LATITUDE}, {CAMPUS_LONGITUDE})...")
    osm_data = fetch_osm_data()

    nodes, adjacency = build_graph(osm_data)
    print(f"Raw graph: {len(adjacency)} nodes with edges")

    keep_ids = largest_connected_component(adjacency)
    print(f"Largest connected component: {len(keep_ids)} nodes "
          f"({len(adjacency) - len(keep_ids)} disconnected nodes dropped)")

    write_graph_file(output_path, nodes, adjacency, keep_ids)
    print(f"Wrote {output_path}")


if __name__ == "__main__":
    main()
