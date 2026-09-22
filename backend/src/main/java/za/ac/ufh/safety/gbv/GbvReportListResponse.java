package za.ac.ufh.safety.gbv;

import java.util.List;

public record GbvReportListResponse(List<GbvReportItem> items, int page, int pageSize, long totalItems) {
}
