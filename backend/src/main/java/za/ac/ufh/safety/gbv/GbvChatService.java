package za.ac.ufh.safety.gbv;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;

/**
 * GBV report chat - docs/api-contract.md section 7a. Deliberately a
 * different shape from every other chat in this app: scoped to a report's
 * referenceCode, not a student account, and never carries a reporter's
 * name anywhere an officer can see it. Only offered for a report submitted
 * with anonymous: false; an anonymous report has no chat, ever, because
 * there is no channel back to an anonymous reporter by design.
 */
@Service
public class GbvChatService {

    private final GbvReportService reportService;
    private final GbvMessageRepository messages;

    public GbvChatService(GbvReportService reportService, GbvMessageRepository messages) {
        this.reportService = reportService;
        this.messages = messages;
    }

    /** Public, given the code - "the code is the credential" model. */
    @Transactional(readOnly = true)
    public GbvMessagesResponse getMessages(String referenceCode) {
        GbvReport report = requireNonAnonymousReport(referenceCode);
        return messagesResponse(report.getReferenceCode());
    }

    /** Public, same rules as above. sender is always "reporter" here. */
    @Transactional
    public GbvMessageResponse sendMessage(String referenceCode, GbvSendMessageRequest request) {
        GbvReport report = requireNonAnonymousReport(referenceCode);
        GbvMessage saved = messages.save(newMessage(report.getReferenceCode(), "reporter", request.text()));
        return GbvMessageResponse.of(saved);
    }

    /** gbv_officer/admin only - same data as the public endpoint, separate path for separate auth/rate-limit rules. */
    @Transactional(readOnly = true)
    public GbvMessagesResponse getMessagesForOfficer(String email, String referenceCode) {
        reportService.requireGbvOfficer(email);
        GbvReport report = reportService.requireReport(referenceCode);
        return messagesResponse(report.getReferenceCode());
    }

    /** gbv_officer/admin only. sender is always "gbv_officer" here. */
    @Transactional
    public GbvMessageResponse sendMessageAsOfficer(String email, String referenceCode, GbvSendMessageRequest request) {
        reportService.requireGbvOfficer(email);
        GbvReport report = reportService.requireReport(referenceCode);
        GbvMessage saved = messages.save(newMessage(report.getReferenceCode(), "gbv_officer", request.text()));
        return GbvMessageResponse.of(saved);
    }

    /**
     * Every non-anonymous report that has at least one message, most recent
     * activity first - reference codes only, never a name, same rule as the
     * case queue.
     */
    @Transactional(readOnly = true)
    public GbvChatQueueResponse getChatQueue(String email) {
        reportService.requireGbvOfficer(email);

        Map<String, MutableEntry> byReport = new LinkedHashMap<>();
        for (GbvMessage message : messages.findAll()) {
            MutableEntry entry = byReport.computeIfAbsent(message.getReferenceCode(), MutableEntry::new);
            if (entry.lastActivityAt == null || message.getSentAt().isAfter(entry.lastActivityAt)) {
                entry.lastActivityAt = message.getSentAt();
            }
            if ("reporter".equals(message.getSender())) {
                entry.hasUnread = true;
            }
        }

        List<GbvChatQueueResponse.Item> items = byReport.values().stream()
            .map(entry -> new GbvChatQueueResponse.Item(entry.referenceCode, entry.lastActivityAt, entry.hasUnread))
            .sorted(Comparator.comparing(GbvChatQueueResponse.Item::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

        return new GbvChatQueueResponse(items);
    }

    private GbvReport requireNonAnonymousReport(String referenceCode) {
        GbvReport report = reportService.requireReport(referenceCode);
        if (Boolean.TRUE.equals(report.getAnonymous())) {
            throw new ApiException(403, "FORBIDDEN", "Anonymous reports do not have a chat.", null);
        }
        return report;
    }

    private GbvMessagesResponse messagesResponse(String referenceCode) {
        List<GbvMessageResponse> items = messages.findByReferenceCodeOrderBySentAtAsc(referenceCode).stream()
            .map(GbvMessageResponse::of)
            .toList();
        return new GbvMessagesResponse(items);
    }

    private GbvMessage newMessage(String referenceCode, String sender, String text) {
        GbvMessage message = new GbvMessage();
        message.setReferenceCode(referenceCode);
        message.setSender(sender);
        message.setText(text);
        return message;
    }

    private static final class MutableEntry {
        final String referenceCode;
        Instant lastActivityAt;
        boolean hasUnread;

        MutableEntry(String referenceCode) {
            this.referenceCode = referenceCode;
        }
    }
}
