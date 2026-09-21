package za.ac.ufh.safety.safetywalk;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/safewalks")
public class SafeWalkController {

    private final SafeWalkService safeWalkService;

    public SafeWalkController(SafeWalkService safeWalkService) {
        this.safeWalkService = safeWalkService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SafeWalkResponse start(Authentication authentication,
                                  @Valid @RequestBody StartSafeWalkRequest request) {
        return safeWalkService.start(authentication.getName(), request);
    }

    @PatchMapping("/{walkId}/location")
    public SafeWalkResponse updateLocation(Authentication authentication,
                                           @PathVariable Long walkId,
                                           @Valid @RequestBody UpdateSafeWalkLocationRequest request) {
        return safeWalkService.updateLocation(authentication.getName(), walkId, request);
    }

    @PostMapping("/{walkId}/arrived")
    public SafeWalkResponse arrived(Authentication authentication, @PathVariable Long walkId) {
        return safeWalkService.markArrived(authentication.getName(), walkId);
    }

    @PostMapping("/{walkId}/cancel")
    public SafeWalkResponse cancel(Authentication authentication, @PathVariable Long walkId) {
        return safeWalkService.cancel(authentication.getName(), walkId);
    }

    // GET /api/safewalks/active - registered before {walkId} is ever reached
    // by the router since it has no path variable segment at this depth.
    @GetMapping("/active")
    public ActiveSafeWalksResponse active(Authentication authentication) {
        return safeWalkService.listActive(authentication.getName());
    }
}
