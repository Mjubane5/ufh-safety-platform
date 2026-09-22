package za.ac.ufh.safety.healthprofile;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/students/me/health")
public class HealthProfileController {

    private final HealthProfileService healthProfileService;

    public HealthProfileController(HealthProfileService healthProfileService) {
        this.healthProfileService = healthProfileService;
    }

    @GetMapping
    public HealthProfileResponse get(Authentication authentication) {
        return healthProfileService.getMyProfile(authentication.getName());
    }

    @PutMapping
    public HealthProfileResponse update(Authentication authentication, @Valid @RequestBody UpdateHealthProfileRequest request) {
        return healthProfileService.updateMyProfile(authentication.getName(), request);
    }
}
