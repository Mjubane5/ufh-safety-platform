package za.ac.ufh.safety.gbv;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/gbv/chat-queue - gbv_officer/admin only. Reference codes only,
 * never a name - same rule as GbvReportItem.
 */
public record GbvChatQueueResponse(List<Item> items) {

    public record Item(String referenceCode, Instant lastActivityAt, boolean hasUnread) {
    }
}
