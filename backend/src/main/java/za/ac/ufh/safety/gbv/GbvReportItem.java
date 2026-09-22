package za.ac.ufh.safety.gbv;

import java.time.Instant;

/**
 * The officer-queue shape from GET /api/gbv/reports. Deliberately has no
 * reporterUserId field at all - not merely null, structurally absent, so a
 * future change to GbvReportService cannot leak it here by accident.
 */
public record GbvReportItem(
        String referenceCode,
        GbvReportStatus status,
        String description,
        Instant occurredAt,
        Double latitude,
        Double longitude,
        Boolean anonymous,
        String contactPreference,
        Instant submittedAt,
        Instant lastUpdatedAt
) {

    static GbvReportItem of(GbvReport report) {
        return new GbvReportItem(
                report.getReferenceCode(),
                report.getStatus(),
                report.getDescription(),
                report.getOccurredAt(),
                report.getLatitude(),
                report.getLongitude(),
                report.getAnonymous(),
                report.getContactPreference(),
                report.getSubmittedAt(),
                report.getLastUpdatedAt());
    }
}
