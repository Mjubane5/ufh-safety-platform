package za.ac.ufh.safety.wellness;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wellness")
public class WellnessController {

    private final WellnessService wellnessService;

    public WellnessController(WellnessService wellnessService) {
        this.wellnessService = wellnessService;
    }

    @GetMapping("/resources")
    public WellnessResourcesResponse resources() {
        return wellnessService.getResources();
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public WellnessBookingResponse createBooking(Authentication authentication,
                                                 @Valid @RequestBody CreateWellnessBookingRequest request) {
        return wellnessService.createBooking(authentication.getName(), request);
    }

    // Shared with the Health Centre: a booking for resourceId 3 is managed
    // by health_officer instead of scu_officer, enforced in the service.
    @PatchMapping("/bookings/{bookingId}")
    public WellnessBookingResponse updateBookingStatus(Authentication authentication,
                                                        @PathVariable Long bookingId,
                                                        @Valid @RequestBody UpdateBookingStatusRequest request) {
        return wellnessService.updateBookingStatus(authentication.getName(), bookingId, request);
    }

    @GetMapping("/messages")
    public WellnessMessagesResponse myMessages(Authentication authentication) {
        return wellnessService.getMyMessages(authentication.getName());
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public WellnessMessageResponse sendMyMessage(Authentication authentication,
                                                 @Valid @RequestBody SendMessageRequest request) {
        return wellnessService.sendMyMessage(authentication.getName(), request);
    }

    @GetMapping("/queue")
    public WellnessQueueResponse queue(Authentication authentication) {
        return wellnessService.getQueue(authentication.getName());
    }

    @GetMapping("/messages/{studentUserId}")
    public WellnessMessagesResponse messagesWithStudent(Authentication authentication, @PathVariable Long studentUserId) {
        return wellnessService.getMessagesWithStudent(authentication.getName(), studentUserId);
    }

    @PostMapping("/messages/{studentUserId}")
    @ResponseStatus(HttpStatus.CREATED)
    public WellnessMessageResponse sendMessageToStudent(Authentication authentication,
                                                        @PathVariable Long studentUserId,
                                                        @Valid @RequestBody SendMessageRequest request) {
        return wellnessService.sendMessageToStudent(authentication.getName(), studentUserId, request);
    }
}
