package za.ac.ufh.safety.gbv;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

/**
 * Confidential GBV report submission, status lookup, and the officer case
 * queue. Deliberately its own table and its own access rules - see
 * docs/api-contract.md section 7. Chat lives in GbvChatService; this class
 * never touches GbvMessage.
 */
@Service
public class GbvReportService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    // No 0/O/1/I - easy to misread on a phone, easy to mistype from memory.
    // Matches frontend/js/api.js's GBV_CODE_CHARS (the mock generator this
    // replaces), so a real code and a demo one look identical.
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_GENERATION_ATTEMPTS = 10;

    private static final Set<String> VALID_CONTACT_PREFERENCES = Set.of("none", "email", "phone");

    private final GbvReportRepository reports;
    private final UserRepository users;
    private final GbvStatusLookupRateLimiter rateLimiter;
    private final SecureRandom random = new SecureRandom();

    public GbvReportService(GbvReportRepository reports, UserRepository users, GbvStatusLookupRateLimiter rateLimiter) {
        this.reports = reports;
        this.users = users;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public GbvReportCreatedResponse submit(Authentication authentication, SubmitGbvReportRequest request) {
        validate(request);

        Long reporterUserId = null;
        if (!Boolean.TRUE.equals(request.anonymous())) {
            // Contract: authenticated when anonymous is false. A caller who
            // claims a non-anonymous report but sent no token gets a normal
            // 401, same as any other protected endpoint.
            String email = GbvAuth.authenticatedEmail(authentication)
                .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED",
                    "Sign in to submit a non-anonymous report, or submit anonymously.", null));
            User reporter = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));
            reporterUserId = reporter.getUserId();
        }

        GbvReport report = new GbvReport();
        report.setReferenceCode(generateUniqueReferenceCode());
        report.setDescription(trimToNull(request.description()));
        report.setOccurredAt(request.occurredAt());
        report.setLatitude(request.latitude());
        report.setLongitude(request.longitude());
        report.setAnonymous(request.anonymous());
        report.setContactPreference(request.contactPreference() == null ? "none" : request.contactPreference());
        report.setReporterUserId(reporterUserId);
        report.setStatus(GbvReportStatus.SUBMITTED);

        GbvReport saved = reports.save(report);
        return new GbvReportCreatedResponse(saved.getReferenceCode(), saved.getStatus(), saved.getSubmittedAt());
    }

    @Transactional(readOnly = true)
    public GbvReportStatusResponse getStatus(String referenceCode, String clientIp) {
        if (!rateLimiter.allow(clientIp)) {
            throw new ApiException(429, "TOO_MANY_ATTEMPTS", "Too many status checks. Try again in a minute.", null);
        }
        GbvReport report = requireReport(referenceCode);
        return new GbvReportStatusResponse(report.getReferenceCode(), report.getStatus(), report.getLastUpdatedAt(), report.getAnonymous());
    }

    @Transactional(readOnly = true)
    public GbvReportListResponse getQueue(String email, String statusParam, Integer page, Integer pageSize) {
        requireGbvOfficer(email);

        GbvReportStatus status = parseStatus(statusParam);
        int pageNumber = page == null ? 1 : page;
        if (pageNumber < 1) {
            throw new ApiException(400, "VALIDATION_FAILED", "page starts at 1.", "page");
        }
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(400, "VALIDATION_FAILED",
                "pageSize must be between 1 and " + MAX_PAGE_SIZE + ".", "pageSize");
        }

        Pageable pageable = PageRequest.of(pageNumber - 1, size);
        Page<GbvReport> found = status == null ? reports.findAll(pageable) : reports.findByStatus(status, pageable);

        return new GbvReportListResponse(
            found.getContent().stream().map(GbvReportItem::of).toList(),
            pageNumber, size, found.getTotalElements());
    }

    @Transactional
    public GbvReportStatusResponse updateStatus(String email, String referenceCode, UpdateGbvStatusRequest request) {
        requireGbvOfficer(email);
        GbvReport report = requireReport(referenceCode);
        report.setStatus(request.status());
        GbvReport saved = reports.save(report);
        return new GbvReportStatusResponse(saved.getReferenceCode(), saved.getStatus(), saved.getLastUpdatedAt(), saved.getAnonymous());
    }

    GbvReport requireReport(String referenceCode) {
        return reports.findByReferenceCode(referenceCode)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No report was found for that reference code.", "referenceCode"));
    }

    private void validate(SubmitGbvReportRequest request) {
        boolean hasLatitude = request.latitude() != null;
        boolean hasLongitude = request.longitude() != null;
        if (hasLatitude != hasLongitude) {
            throw new ApiException(400, "VALIDATION_FAILED",
                "Send both latitude and longitude, or neither.", hasLatitude ? "longitude" : "latitude");
        }

        String contactPreference = request.contactPreference();
        if (contactPreference != null && !VALID_CONTACT_PREFERENCES.contains(contactPreference)) {
            throw new ApiException(400, "VALIDATION_FAILED",
                "contactPreference must be none, email, or phone.", "contactPreference");
        }
        if (Boolean.TRUE.equals(request.anonymous()) && contactPreference != null && !"none".equals(contactPreference)) {
            throw new ApiException(400, "VALIDATION_FAILED",
                "contactPreference must be none when the report is anonymous.", "contactPreference");
        }
    }

    private String generateUniqueReferenceCode() {
        for (int attempt = 0; attempt < CODE_GENERATION_ATTEMPTS; attempt++) {
            String candidate = "GBV-" + randomPart() + "-" + randomPart();
            if (!reports.existsByReferenceCode(candidate)) {
                return candidate;
            }
        }
        // Astronomically unlikely with a ~33^8 code space, but never loop
        // forever on a lookup instead of failing loudly.
        throw new ApiException(500, "SERVER_ERROR", "Could not generate a unique reference code. Try again.", null);
    }

    private String randomPart() {
        StringBuilder part = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            part.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return part.toString();
    }

    private GbvReportStatus parseStatus(String statusParam) {
        String value = trimToNull(statusParam);
        if (value == null) return null;
        try {
            return GbvReportStatus.fromWireValue(value);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "VALIDATION_FAILED", "Unknown status filter: " + value, "status");
        }
    }

    void requireGbvOfficer(String email) {
        User user = users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));
        if (!"gbv_officer".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Your role cannot access GBV case content.", null);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
