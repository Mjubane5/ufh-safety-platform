package za.ac.ufh.safety.safetywalk;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SafetyWalkController {

    private final SafetyWalkService safetyWalkService;
    private final HotspotService hotspotService;
    private final SafeRouteService safeRouteService;

    public SafetyWalkController(
            SafetyWalkService safetyWalkService,
            HotspotService hotspotService,
            SafeRouteService safeRouteService) {

        this.safetyWalkService = safetyWalkService;
        this.hotspotService = hotspotService;
        this.safeRouteService = safeRouteService;
    }

    @PostMapping("/patrols")
    @ResponseStatus(HttpStatus.CREATED)
    public PatrolCreatedResponse createPatrol(
            Authentication authentication,
            @Valid @RequestBody PatrolCreateRequest request) {

        return safetyWalkService.createPatrol(
                authentication.getName(),
                request
        );
    }

    @GetMapping("/patrols/recent")
    public PatrolRecentResponse getRecentPatrols(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(required = false) Double radiusMetres) {

        return safetyWalkService.getRecentPatrols(
                latitude,
                longitude,
                radiusMetres
        );
    }

    @GetMapping("/hotspots")
    public Map<String, Object> getHotspots() {

        return Map.of(
                "items",
                hotspotService.getHotspots()
        );
    }

    @PostMapping("/routes/safe")
    public SafeRouteResponse getSafeRoute(
            @Valid @RequestBody SafeRouteRequest request) {

        return safeRouteService.findSafeRoute(request);
    }
}