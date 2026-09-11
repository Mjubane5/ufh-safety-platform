package za.ac.ufh.safety.incidents;

/**
 * Body of POST /api/incidents/{incidentId}/assign.
 *
 * `responderId` is nullable and the null case is the whole design of this
 * endpoint, not an afterthought:
 *
 *   present -> the dispatcher has chosen this person by hand
 *   absent  -> the backend picks the nearest available responder
 *
 * The two paths have different rules when the incident has no coordinates.
 * See IncidentAssignmentService.
 */
public record AssignIncidentRequest(Long responderId) {}
