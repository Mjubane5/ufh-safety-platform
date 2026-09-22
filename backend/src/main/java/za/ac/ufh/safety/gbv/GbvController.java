package za.ac.ufh.safety.gbv;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gbv")
public class GbvController {

    private final GbvReportService reportService;
    private final GbvChatService chatService;

    public GbvController(GbvReportService reportService, GbvChatService chatService) {
        this.reportService = reportService;
        this.chatService = chatService;
    }

    // Public, or authenticated when anonymous is false - enforced inside
    // the service, since Spring Security's permitAll rule below cannot see
    // the request body to decide.
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public GbvReportCreatedResponse submit(Authentication authentication,
                                           @Valid @RequestBody SubmitGbvReportRequest request) {
        return reportService.submit(authentication, request);
    }

    @GetMapping("/reports/{referenceCode}/status")
    public GbvReportStatusResponse status(@PathVariable String referenceCode, HttpServletRequest httpRequest) {
        return reportService.getStatus(referenceCode, clientIp(httpRequest));
    }

    @GetMapping("/reports")
    public GbvReportListResponse queue(Authentication authentication,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer pageSize) {
        return reportService.getQueue(authentication.getName(), status, page, pageSize);
    }

    @PatchMapping("/reports/{referenceCode}/status")
    public GbvReportStatusResponse updateStatus(Authentication authentication,
                                                @PathVariable String referenceCode,
                                                @Valid @RequestBody UpdateGbvStatusRequest request) {
        return reportService.updateStatus(authentication.getName(), referenceCode, request);
    }

    @GetMapping("/reports/{referenceCode}/messages")
    public GbvMessagesResponse messages(@PathVariable String referenceCode) {
        return chatService.getMessages(referenceCode);
    }

    @PostMapping("/reports/{referenceCode}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public GbvMessageResponse sendMessage(@PathVariable String referenceCode,
                                          @Valid @RequestBody GbvSendMessageRequest request) {
        return chatService.sendMessage(referenceCode, request);
    }

    @GetMapping("/chat-queue")
    public GbvChatQueueResponse chatQueue(Authentication authentication) {
        return chatService.getChatQueue(authentication.getName());
    }

    @GetMapping("/reports/{referenceCode}/messages/officer")
    public GbvMessagesResponse messagesForOfficer(Authentication authentication, @PathVariable String referenceCode) {
        return chatService.getMessagesForOfficer(authentication.getName(), referenceCode);
    }

    @PostMapping("/reports/{referenceCode}/messages/officer")
    @ResponseStatus(HttpStatus.CREATED)
    public GbvMessageResponse sendMessageAsOfficer(Authentication authentication,
                                                   @PathVariable String referenceCode,
                                                   @Valid @RequestBody GbvSendMessageRequest request) {
        return chatService.sendMessageAsOfficer(authentication.getName(), referenceCode, request);
    }

    // X-Forwarded-For first: this app is expected to sit behind Railway's
    // proxy in production (see README-BACKEND.md), where getRemoteAddr()
    // would return the proxy's own address for every caller and the rate
    // limit would apply to everyone as one bucket instead of per visitor.
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
