package za.ac.ufh.safety.responders;

import java.util.List;

/**
 * Wrapped in an object with an `items` key rather than returned as a bare
 * JSON array, matching GET /api/incidents. A top-level array cannot grow a
 * field later without breaking every caller.
 */
public record ResponderListResponse(List<ResponderSummary> items) {}
