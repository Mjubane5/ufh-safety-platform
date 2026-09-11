package za.ac.ufh.safety.incidents;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    // authentication.getName() is the email the JWT filter put there. The
    // client never tells us who it is; we read it from the verified token.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentCreatedResponse report(Authentication authentication,
                                          @Valid @RequestBody CreateIncidentRequest request) {
        return incidentService.create(authentication.getName(), request);
    }

    @GetMapping
    public IncidentListResponse list(Authentication authentication,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer pageSize) {
        return incidentService.list(authentication.getName(), status, page, pageSize);
    }

    @GetMapping("/{incidentId}")
    public IncidentDetail detail(Authentication authentication,
                                 @PathVariable Long incidentId) {
        return incidentService.get(authentication.getName(), incidentId);
    }

    @PatchMapping("/{incidentId}/status")
    public IncidentDetail updateStatus(Authentication authentication,
                                       @PathVariable Long incidentId,
                                       @Valid @RequestBody UpdateStatusRequest request) {
        return incidentService.updateStatus(authentication.getName(), incidentId, request);
    }

    // A cancel with no body at all is allowed: reason is optional, and the
    // point of this endpoint is to stop an alarm quickly.
    @PostMapping("/{incidentId}/cancel")
    public IncidentDetail cancel(Authentication authentication,
                                 @PathVariable Long incidentId,
                                 @Valid @RequestBody(required = false) CancelIncidentRequest request) {
        CancelIncidentRequest body = request == null ? new CancelIncidentRequest(null) : request;
        return incidentService.cancel(authentication.getName(), incidentId, body);
    }
}
