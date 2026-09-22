package za.ac.ufh.safety.health;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/messages")
    public HealthMessagesResponse myMessages(Authentication authentication) {
        return healthService.getMyMessages(authentication.getName());
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public HealthMessageResponse sendMyMessage(Authentication authentication,
                                               @Valid @RequestBody SendMessageRequest request) {
        return healthService.sendMyMessage(authentication.getName(), request);
    }

    @GetMapping("/queue")
    public HealthQueueResponse queue(Authentication authentication) {
        return healthService.getQueue(authentication.getName());
    }

    @GetMapping("/messages/{studentUserId}")
    public HealthMessagesResponse messagesWithStudent(Authentication authentication, @PathVariable Long studentUserId) {
        return healthService.getMessagesWithStudent(authentication.getName(), studentUserId);
    }

    @PostMapping("/messages/{studentUserId}")
    @ResponseStatus(HttpStatus.CREATED)
    public HealthMessageResponse sendMessageToStudent(Authentication authentication,
                                                       @PathVariable Long studentUserId,
                                                       @Valid @RequestBody SendMessageRequest request) {
        return healthService.sendMessageToStudent(authentication.getName(), studentUserId, request);
    }
}
