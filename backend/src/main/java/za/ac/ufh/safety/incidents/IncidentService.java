package za.ac.ufh.safety.incidents;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.responders.ResponderStatus;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

@Service
public class IncidentService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final IncidentRepository incidents;
    private final UserRepository users;
    private final ResponderRepository responders;
    private final PriorityCalculator priorityCalculator;

    public IncidentService(IncidentRepository incidents,
                           UserRepository users,
                           ResponderRepository responders,
                           PriorityCalculator priorityCalculator) {
        this.incidents = incidents;
        this.users = users;
        this.responders = responders;
        this.priorityCalculator = priorityCalculator;
    }

    @Transactional
    public IncidentCreatedResponse create(String email, CreateIncidentRequest request) {
        User reporter = requireUser(email);

        // Contract: only a student files a report. Campus control and
        // responders act on incidents, they do not raise them.
        if (!"student".equals(reporter.getRole())) {
            throw new ApiException(403, "FORBIDDEN",
                "Only a student account can report an incident.", null);
        }

        validate(request);

        Incident incident = new Incident();
        incident.setReporterUserId(reporter.getUserId());
        incident.setType(request.type());
        incident.setDescription(trimToNull(request.description()));
        incident.setLatitude(request.latitude());
        incident.setLongitude(request.longitude());
        incident.setAccuracy(request.accuracy());
        incident.setAnonymous(request.anonymous());
        incident.setStatus(IncidentStatus.REPORTED);
        incident.setPriority(priorityCalculator.calculate(request.type()));

        Incident saved = incidents.save(incident);
        return new IncidentCreatedResponse(
            saved.getIncidentId(),
            saved.getType(),
            saved.getStatus(),
            saved.getPriority(),
            saved.getLocationSource(),
            saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public IncidentListResponse list(String email, String statusParam, Integer page, Integer pageSize) {
        User caller = requireUser(email);
        IncidentStatus status = parseStatus(statusParam);

        int pageNumber = page == null ? 1 : page;
        if (pageNumber < 1) {
            throw new ApiException(400, "VALIDATION_FAILED", "page starts at 1.", "page");
        }
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(400, "VALIDATION_FAILED",
                "pageSize must be between 1 and " + MAX_PAGE_SIZE + ".", "pageSize");
        }

        // The contract pages from 1, Spring Data pages from 0.
        // Most urgent first, then newest, so a dispatcher reads the top of the
        // list and is looking at the thing that matters most right now.
        Pageable pageable = PageRequest.of(pageNumber - 1, size,
            Sort.by(Sort.Order.asc("priority"), Sort.Order.desc("createdAt")));

        Page<Incident> found = findForRole(caller, status, pageable);

        List<IncidentSummary> items = found.getContent().stream()
            .map(IncidentSummary::of)
            .toList();

        return new IncidentListResponse(items, pageNumber, size, found.getTotalElements());
    }

    /**
     * Who is allowed to see what. This is the whole access rule for the list,
     * kept in one place: a student sees only what they reported, a responder
     * only what was assigned to them, campus control and admin see everything.
     * The filter is applied in the query rather than after it, so a caller
     * cannot page their way into somebody else's incidents.
     */
    private Page<Incident> findForRole(User caller, IncidentStatus status, Pageable pageable) {
        return switch (caller.getRole()) {
            case "student" -> status == null
                ? incidents.findByReporterUserId(caller.getUserId(), pageable)
                : incidents.findByReporterUserIdAndStatus(caller.getUserId(), status, pageable);
            case "responder" -> status == null
                ? incidents.findByAssignedResponderId(caller.getUserId(), pageable)
                : incidents.findByAssignedResponderIdAndStatus(caller.getUserId(), status, pageable);
            case "campus_control", "admin" -> status == null
                ? incidents.findAll(pageable)
                : incidents.findByStatus(status, pageable);
            default -> throw new ApiException(403, "FORBIDDEN",
                "Your role cannot view incidents.", null);
        };
    }

    @Transactional(readOnly = true)
    public IncidentDetail get(String email, Long incidentId) {
        User caller = requireUser(email);
        Incident incident = requireIncident(incidentId);
        requireCanView(caller, incident);
        return toDetail(incident);
    }

    /**
     * PATCH /api/incidents/{incidentId}/status.
     *
     * Campus control and admin can move any incident. A responder can only
     * move one that is assigned to them, so a responder cannot reach into
     * somebody else's call and mark it resolved.
     */
    @Transactional
    public IncidentDetail updateStatus(String email, Long incidentId, UpdateStatusRequest request) {
        User caller = requireUser(email);
        Incident incident = requireIncident(incidentId);

        switch (caller.getRole()) {
            case "campus_control", "admin" -> { }
            case "responder" -> {
                if (!caller.getUserId().equals(incident.getAssignedResponderId())) {
                    throw new ApiException(403, "FORBIDDEN",
                        "This incident is not assigned to you.", null);
                }
            }
            default -> throw new ApiException(403, "FORBIDDEN",
                "Your role cannot change an incident status.", null);
        }

        IncidentStatus target = request.status();
        IncidentStatus current = incident.getStatus();

        // Asking for the status it already has is not an error, it is a no-op.
        // A responder tapping "en route" twice on a bad signal should not see
        // a failure.
        if (current == target) {
            return toDetail(incident);
        }

        if (StatusTransitions.isTerminal(current)) {
            throw new ApiException(409, "CONFLICT",
                "This incident is already " + current.wireValue() + " and cannot change.", "status");
        }
        if (!StatusTransitions.isLegal(current, target)) {
            throw new ApiException(409, "CONFLICT",
                "Cannot move an incident from " + current.wireValue()
                    + " to " + target.wireValue() + ".", "status");
        }

        incident.setStatus(target);
        releaseResponderIfFinished(incident);
        return toDetail(incidents.save(incident));
    }

    /**
     * POST /api/incidents/{incidentId}/cancel — the false-alarm path.
     *
     * Student-only and own-incident-only by contract. The row is kept and the
     * status set to cancelled; incidents are never hard-deleted, because a
     * report that vanishes is a report nobody can review afterwards.
     */
    @Transactional
    public IncidentDetail cancel(String email, Long incidentId, CancelIncidentRequest request) {
        User caller = requireUser(email);
        Incident incident = requireIncident(incidentId);

        if (!"student".equals(caller.getRole())) {
            throw new ApiException(403, "FORBIDDEN",
                "Only the student who reported an incident can cancel it.", null);
        }
        if (!caller.getUserId().equals(incident.getReporterUserId())) {
            throw new ApiException(403, "FORBIDDEN",
                "You can only cancel an incident you reported.", null);
        }

        IncidentStatus current = incident.getStatus();
        if (current == IncidentStatus.CANCELLED) {
            return toDetail(incident);
        }
        if (StatusTransitions.isTerminal(current)) {
            throw new ApiException(409, "CONFLICT",
                "This incident is already " + current.wireValue() + " and cannot be cancelled.", null);
        }

        incident.setStatus(IncidentStatus.CANCELLED);
        releaseResponderIfFinished(incident);
        return toDetail(incidents.save(incident));
    }

    /**
     * Puts a responder back in the pool once their incident is finished.
     *
     * Assignment takes somebody out of the pool. If nothing ever puts them
     * back, the roster drains: every assignment removes a person permanently
     * and after a handful of incidents auto-assignment returns 404 for
     * everything. That is the kind of fault that appears during a live
     * demonstration rather than before one.
     *
     * The responder keeps the incident's assignedResponderId — the record of
     * who attended is worth keeping. Only their duty status changes.
     *
     * A responder who has since been marked off duty is left alone, because
     * finishing a call is not a reason to put somebody back on shift.
     */
    private void releaseResponderIfFinished(Incident incident) {
        if (!StatusTransitions.isTerminal(incident.getStatus())) return;
        if (incident.getAssignedResponderId() == null) return;

        responders.findById(incident.getAssignedResponderId()).ifPresent(responder -> {
            if (responder.getStatus() == ResponderStatus.ASSIGNED) {
                responder.setStatus(ResponderStatus.AVAILABLE);
                responders.save(responder);
            }
        });
    }

    /**
     * Who may read one incident. Same rule as the list, restated for a single
     * row: a student sees only their own, a responder only their assignment.
     *
     * A caller who is not allowed to see the incident gets 404, not 403. 403
     * would confirm that an incident with that id exists, which lets somebody
     * count incidents by walking ids.
     */
    private void requireCanView(User caller, Incident incident) {
        boolean allowed = switch (caller.getRole()) {
            case "campus_control", "admin" -> true;
            case "student" -> caller.getUserId().equals(incident.getReporterUserId());
            case "responder" -> caller.getUserId().equals(incident.getAssignedResponderId());
            default -> false;
        };
        if (!allowed) {
            throw new ApiException(404, "NOT_FOUND", "No such incident.", null);
        }
    }

    private Incident requireIncident(Long incidentId) {
        return incidents.findById(incidentId)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No such incident.", null));
    }

    /**
     * Loads the two people named on an incident so the detail response can
     * carry their names. Both lookups tolerate a missing row and pass null,
     * because the response shape allows null for either.
     */
    private IncidentDetail toDetail(Incident incident) {
        User reporter = incident.getReporterUserId() == null
            ? null
            : users.findById(incident.getReporterUserId()).orElse(null);
        User responder = incident.getAssignedResponderId() == null
            ? null
            : users.findById(incident.getAssignedResponderId()).orElse(null);
        return IncidentDetail.of(incident, reporter, responder);
    }

    private void validate(CreateIncidentRequest request) {
        // "other" carries no meaning on its own. Without a description the
        // dispatcher has a report they cannot act on.
        if (request.type() == IncidentType.OTHER && trimToNull(request.description()) == null) {
            throw new ApiException(400, "VALIDATION_FAILED",
                "Describe what happened when the type is other.", "description");
        }

        // Half a coordinate pair is not a location. Storing it would let the
        // map plot the report on the equator or the prime meridian.
        boolean hasLatitude = request.latitude() != null;
        boolean hasLongitude = request.longitude() != null;
        if (hasLatitude != hasLongitude) {
            throw new ApiException(400, "VALIDATION_FAILED",
                "Send both latitude and longitude, or neither.",
                hasLatitude ? "longitude" : "latitude");
        }
    }

    private IncidentStatus parseStatus(String statusParam) {
        String value = trimToNull(statusParam);
        if (value == null) return null;
        try {
            return IncidentStatus.fromWireValue(value);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "VALIDATION_FAILED",
                "Unknown status filter: " + value, "status");
        }
    }

    private User requireUser(String email) {
        return users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED",
                "Sign in again.", null));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
