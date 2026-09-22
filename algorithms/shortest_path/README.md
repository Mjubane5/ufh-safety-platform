# Shortest-path routing (C++)

Real shortest-path routing over the campus footpath/road graph, replacing
the straight-line-plus-one-detour approximation `SafeRouteService` used
before this existed. A Safe Walk route used to be a literal straight line
between two GPS points — it cut across buildings and open ground instead of
following an actual path. `NearestResponderSelector` has carried this
comment since before any of this was built:

> The real shortest-path work over the campus footpath graph is the C++
> module. This class only chooses *who*; the route between them comes later.

This is that module — wired into Safe Walk routing now. It is not yet used
by `NearestResponderSelector` itself (that class still only picks *which*
responder, by straight-line distance); reusing this same engine to route a
responder to an incident is a natural next step, not assumed here.

## What it does

`shortest_path.cpp` is a single-file C++17 program: plain Dijkstra with a
binary heap over a graph loaded from a text file, snapping the requested
`from`/`to` coordinates to their nearest graph node. Optional hotspot
arguments bias the search — an edge whose midpoint falls inside a hotspot's
radius gets its cost multiplied up, so the search naturally prefers a longer
physical path over a shorter one that walks through danger, instead of
computing a plain shortest path and scoring it afterwards.

```
shortest_path <graph_file> <fromLat> <fromLon> <toLat> <toLon> \
    [hotLat hotLon hotRadiusMetres hotWeight]...
```

Outputs one JSON object to stdout — `{"ok":true,"distanceMetres":...,"points":[...]}`
on success, `{"ok":false,"error":"..."}` (and a non-zero exit code) on
failure. See the file's own header comment for the full contract.

## How it's called

`backend/src/main/java/za/ac/ufh/safety/safetywalk/CppRouteEngine.java`
runs the compiled binary once per Safe Walk route request via
`ProcessBuilder`, with a 3-second timeout. Any failure — binary missing,
process won't start, times out, output doesn't parse — is treated as "this
engine is unavailable right now," never an error response: `SafeRouteService`
falls back to `GeometricRouteEngine` (the old straight-line logic)
automatically. A Safe Walk route must never be blocked by this program being
missing, slow, or broken.

This is a deliberate departure from the original stack-table wording ("C++ —
standalone module, *not in the request path*"): a precomputed-offline
approach cannot serve an arbitrary student-clicked destination, only a fixed
set of pairs chosen in advance. The graph is small enough (a few thousand
nodes) that one Dijkstra run costs low single-digit milliseconds, so the
per-request cost is negligible — flagged here as a disclosed change from the
original plan, not a silent one.

## Building it

**Locally**, this is entirely optional — nobody needs a C++ toolchain to run
the backend or to work on Safe Walk. Without a compiled binary,
`app.routing.cpp-binary` stays unset and `SafeRouteService` uses
`GeometricRouteEngine` for every route, same as before this module existed.

To build and try it locally anyway (g++ via MinGW on Windows, or any g++/clang
on macOS/Linux):

```bash
g++ -O2 -std=c++17 -o shortest_path shortest_path.cpp
./shortest_path data/campus_graph.txt -32.7833 26.8497 -32.7855 26.8520
```

Then point the backend at it before starting it:

```powershell
$env:ROUTING_CPP_BINARY="C:\path\to\algorithms\shortest_path\shortest_path.exe"
$env:ROUTING_GRAPH_FILE="C:\path\to\algorithms\shortest_path\data\campus_graph.txt"
```

**In production**, the root `Dockerfile` compiles this in its own Alpine
build stage (so the binary links against musl, matching the
`eclipse-temurin:*-alpine` runtime image) and copies the result in
alongside `app.jar`, setting both environment variables automatically. If
that compile step ever fails, the image still builds and deploys — see the
Dockerfile's own comment on that stage — Safe Walk just falls back to the
geometric route until it's fixed.

## Testing

This program has no automated test suite of its own — a JVM unit test
cannot exercise a native subprocess the way `SafeRouteServiceTest` and
`GeometricRouteEngineTest` test the rest of this feature (189 pure unit
tests, no process spawning, is this project's whole testing philosophy —
see `docs/SOFTWARE-DOCUMENTATION.pdf` section 10). Instead:

- `CppRouteEngineTest` covers the Java side's fail-safe behaviour: every
  "not configured / not a real file" case returns `null` rather than
  throwing, which is the property `SafeRouteService`'s fallback actually
  depends on.
- The algorithm itself (Dijkstra correctness, path reconstruction, the
  hotspot risk penalty actually changing which path wins) was verified by
  hand against a small synthetic graph via an online compiler before this
  was integrated — a 6-node diamond graph with a known shortest path, and a
  hotspot placed to force a specific detour. Both cases passed. This is
  disclosed here as a manual verification step, not hidden as if it were
  covered by CI — there is no CI pipeline in this repository (see
  `docs/SOFTWARE-DOCUMENTATION.pdf` section 11).
- End-to-end (does a route drawn on `map.html` actually follow real paths
  instead of cutting across buildings) needs to be checked against a real
  deployment with the binary compiled in — do this before presenting.

## The graph data

See `data/README.md` for where `campus_graph.txt` comes from and how to
regenerate it.
