package za.ac.ufh.safety.gbv;

import java.time.Instant;

public record GbvReportCreatedResponse(String referenceCode, GbvReportStatus status, Instant submittedAt) {
}
