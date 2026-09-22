# Campus graph data

`campus_graph.txt` is a real walkable-path graph for the area around the
UFH campus, built from actual OpenStreetMap data — not synthetic or
hand-drawn waypoints. This is what makes `shortest_path.cpp`'s output
follow real footways, service roads and streets instead of cutting a
straight line across buildings and open ground, which was the original bug
report this module exists to fix.

## Where it came from

Every OSM way tagged `highway` as one of `footway`, `path`, `pedestrian`,
`service`, `residential`, `living_street`, `track`, `primary`, `secondary`,
`tertiary`, `unclassified`, or `steps`, within 1200 metres of
`CAMPUS_CENTER` (`-32.7833, 26.8497` — the same coordinate
`frontend/js/map.js` already centres the map on), fetched from the
[Overpass API](https://overpass-api.de/). That's **2186 nodes and 4716
directed edges**, all in a single connected component (no isolated
fragments a route could get stuck in), generated on 2026-09-22.

## File format

```
<nodeCount> <edgeCount>
<lat> <lon>                    (one line per node, id = 0-based line order)
...
<fromNodeId> <toNodeId> <weightMetres>   (one line per directed edge)
...
```

Plain text, no JSON/XML parsing needed on the C++ side — `shortest_path.cpp`
reads it with nothing but `<fstream>`. Both directions of a walkable way are
present as separate edges, so the file is self-contained; there is no
separate "is this one-way" flag; pedestrians can use either direction of
every path in this graph, which is the right assumption for a walking app.

## Regenerating it

```bash
python3 generate_graph.py
```

Standard library only — nothing to `pip install`. Re-run this if the
campus footprint changes (a new building, a closed path) or to widen the
`RADIUS_METRES` constant at the top of the script if Safe Walk needs to
reach further off-campus. The script keeps only the largest connected
component on purpose — see its own comment — so a route between any two
points the graph actually contains is always findable.
