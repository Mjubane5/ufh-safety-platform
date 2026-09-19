package za.ac.ufh.safety.safetywalk;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SafetyWalkController {

    private final SafetyWalkService safetyWalkService;

    public SafetyWalkController(
            SafetyWalkService safetyWalkService) {

        this.safetyWalkService = safetyWalkService;
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
}