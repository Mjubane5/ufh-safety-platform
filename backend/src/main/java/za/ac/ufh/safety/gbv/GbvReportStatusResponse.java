package za.ac.ufh.safety.gbv;

import java.time.Instant;

/**
 * GET /api/gbv/reports/{code}/status - status only, never report content,
 * never officer names.
 */
public record GbvReportStatusResponse(String referenceCode, GbvReportStatus status, Instant lastUpdatedAt, Boolean anonymous) {
}
