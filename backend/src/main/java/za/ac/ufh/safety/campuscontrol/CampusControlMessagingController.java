package za.ac.ufh.safety.campuscontrol;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campus-control")
public class CampusControlMessagingController {

    private final CampusControlMessagingService messagingService;

    public CampusControlMessagingController(CampusControlMessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @GetMapping("/messages")
    public CampusControlMessagesResponse myMessages(Authentication authentication) {
        return messagingService.getMyMessages(authentication.getName());
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public CampusControlMessageResponse sendMyMessage(Authentication authentication,
                                                       @Valid @RequestBody SendMessageRequest request) {
        return messagingService.sendMyMessage(authentication.getName(), request);
    }

    @GetMapping("/queue")
    public CampusControlQueueResponse queue(Authentication authentication) {
        return messagingService.getQueue(authentication.getName());
    }

    @GetMapping("/messages/{studentUserId}")
    public CampusControlMessagesResponse messagesWithStudent(Authentication authentication, @PathVariable Long studentUserId) {
        return messagingService.getMessagesWithStudent(authentication.getName(), studentUserId);
    }

    @PostMapping("/messages/{studentUserId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CampusControlMessageResponse sendMessageToStudent(Authentication authentication,
                                                              @PathVariable Long studentUserId,
                                                              @Valid @RequestBody SendMessageRequest request) {
        return messagingService.sendMessageToStudent(authentication.getName(), studentUserId, request);
    }
}
