package za.ac.ufh.safety.incidents;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

/**
 * GET/POST /api/incidents/{id}/signals - the derived distress-detection
 * events from docs/api-contract.md. Reading uses the same visibility as the
 * incident itself (IncidentService.requireVisibleIncident); posting is
 * narrower - only the reporter's own device may add to their own incident's
 * log, not a responder or campus control who can merely view it.
 */
@Service
public class IncidentSignalService {

    private final IncidentSignalRepository signals;
    private final IncidentService incidentService;
    private final UserRepository users;

    public IncidentSignalService(IncidentSignalRepository signals, IncidentService incidentService, UserRepository users) {
        this.signals = signals;
        this.incidentService = incidentService;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public IncidentSignalsResponse list(String email, Long incidentId) {
        incidentService.requireVisibleIncident(email, incidentId);
        var items = signals.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
            .map(IncidentSignalResponse::of)
            .toList();
        return new IncidentSignalsResponse(items);
    }

    @Transactional
    public IncidentSignalResponse append(String email, Long incidentId, AppendSignalRequest request) {
        User caller = requireUser(email);
        Incident incident = incidentService.requireVisibleIncident(email, incidentId);

        if (!"student".equals(caller.getRole()) || !caller.getUserId().equals(incident.getReporterUserId())) {
            throw new ApiException(403, "FORBIDDEN", "Only the reporter can add to this incident's signal log.", null);
        }
        if (StatusTransitions.isTerminal(incident.getStatus())) {
            throw new ApiException(409, "CONFLICT",
                "This incident is already " + incident.getStatus().wireValue() + ".", null);
        }

        IncidentSignal signal = new IncidentSignal();
        signal.setIncidentId(incidentId);
        signal.setType(request.type());
        signal.setLabel(request.label());
        signal.setConfidence(request.confidence());
        signal.setText(request.text());

        return IncidentSignalResponse.of(signals.save(signal));
    }

    private User requireUser(String email) {
        return users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));
    }
}
