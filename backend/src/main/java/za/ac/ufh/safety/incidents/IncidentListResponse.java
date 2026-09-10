package za.ac.ufh.safety.incidents;

import java.util.List;

/** 200 body for GET /api/incidents, contract section 3. */
public record IncidentListResponse(
    List<IncidentSummary> items,
    int page,
    int pageSize,
    long totalItems
) {}
