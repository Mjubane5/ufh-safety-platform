package za.ac.ufh.safety.safetywalk;

import java.util.List;

/**
 * Computes the walking path between two points, optionally biased away from
 * a set of hotspots. Two implementations exist: CppRouteEngine (the real
 * shortest-path search over the campus footpath/road graph) and
 * GeometricRouteEngine (the straight-line-plus-one-detour approximation
 * this codebase used before the C++ module existed). SafeRouteService tries
 * the former first and falls back to the latter - see its class comment.
 *
 * Returning null (never throwing) is how an implementation says "I cannot
 * produce a route right now" - missing binary, process failure, bad output,
 * whatever the reason, it is always recoverable by trying the next engine,
 * never a reason to fail the whole request.
 *
 * Public, and both implementations are too, so za.ac.ufh.safety.incidents.
 * IncidentAssignmentService can reuse the exact same engines to route a
 * responder to an incident after assignment (docs/api-contract.md's
 * AssignmentResponse.route) - the other half of "the C++ shortest-path
 * work" this package's classes have referred to since before either
 * consumer existed.
 */
public interface RouteEngine {

    List<SafeRouteResponse.RoutePoint> findPath(
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude,
            List<Hotspot> hotspots
    );
}
