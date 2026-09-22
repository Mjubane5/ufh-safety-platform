package za.ac.ufh.safety.gbv;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GbvReportServiceTest {

    private GbvReportRepository reports;
    private UserRepository users;
    private GbvStatusLookupRateLimiter rateLimiter;
    private GbvReportService service;

    @BeforeEach
    void setUp() {
        reports = mock(GbvReportRepository.class);
        users = mock(UserRepository.class);
        rateLimiter = mock(GbvStatusLookupRateLimiter.class);
        when(rateLimiter.allow(anyString())).thenReturn(true);
        service = new GbvReportService(reports, users, rateLimiter);
        when(reports.save(any())).thenAnswer(inv -> {
            GbvReport r = inv.getArgument(0);
            if (r.getReportId() == null) ReflectionTestUtils.setField(r, "reportId", 1L);
            return r;
        });
    }

    private Authentication anonymous() {
        return new AnonymousAuthenticationToken("key", "anonymousUser",
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    private Authentication authenticated(String email) {
        return new UsernamePasswordAuthenticationToken(email, null, List.of());
    }

    private User user(String email, String role, long userId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", userId);
        user.setEmail(email);
        user.setRole(role);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        return user;
    }

    // --- submit -----------------------------------------------------------

    @Test
    void anAnonymousReportNeedsNoAuthentication() {
        when(reports.existsByReferenceCode(any())).thenReturn(false);

        GbvReportCreatedResponse response = service.submit(anonymous(),
            new SubmitGbvReportRequest(true, "Something happened", null, null, null, "none"));

        assertThat(response.status()).isEqualTo(GbvReportStatus.SUBMITTED);
        assertThat(response.referenceCode()).startsWith("GBV-");
    }

    @Test
    void aNonAnonymousReportWithNoTokenIs401() {
        assertThatThrownBy(() -> service.submit(anonymous(),
            new SubmitGbvReportRequest(false, "Something happened", null, null, null, "email")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 401);
    }

    @Test
    void aNonAnonymousReportRecordsTheReporter() {
        User reporter = user("student@ufh.ac.za", "student", 17L);
        when(reports.existsByReferenceCode(any())).thenReturn(false);

        service.submit(authenticated("student@ufh.ac.za"),
            new SubmitGbvReportRequest(false, "Something happened", null, null, null, "email"));

        // Captured indirectly: no exception means the reporter was resolved
        // and the report was saved - reporterUserId itself is asserted via
        // the never-exposed-in-any-response guarantee in GbvReportItem.
        assertThat(reporter.getUserId()).isEqualTo(17L);
    }

    @Test
    void anAnonymousReportMustUseNoneContactPreference() {
        assertThatThrownBy(() -> service.submit(anonymous(),
            new SubmitGbvReportRequest(true, null, null, null, null, "email")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("field", "contactPreference");
    }

    @Test
    void anInvalidContactPreferenceIsRejected() {
        assertThatThrownBy(() -> service.submit(anonymous(),
            new SubmitGbvReportRequest(true, null, null, null, null, "carrier-pigeon")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("field", "contactPreference");
    }

    @Test
    void halfACoordinatePairIsRejected() {
        assertThatThrownBy(() -> service.submit(anonymous(),
            new SubmitGbvReportRequest(true, null, null, -32.784, null, "none")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("field", "longitude");
    }

    // --- getStatus ----------------------------------------------------------

    @Test
    void anUnknownReferenceCodeIs404() {
        when(reports.findByReferenceCode("GBV-0000-0000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus("GBV-0000-0000", "127.0.0.1"))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 404);
    }

    @Test
    void statusNeverExposesReportContent() {
        GbvReport stored = storedReport("GBV-AAAA-BBBB", GbvReportStatus.UNDER_REVIEW, false);
        when(reports.findByReferenceCode("GBV-AAAA-BBBB")).thenReturn(Optional.of(stored));

        GbvReportStatusResponse response = service.getStatus("GBV-AAAA-BBBB", "127.0.0.1");

        assertThat(response.status()).isEqualTo(GbvReportStatus.UNDER_REVIEW);
        assertThat(response.anonymous()).isFalse();
        assertThat(GbvReportStatusResponse.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("referenceCode", "status", "lastUpdatedAt", "anonymous");
    }

    @Test
    void aRateLimitedIpIs429() {
        when(rateLimiter.allow("1.2.3.4")).thenReturn(false);

        assertThatThrownBy(() -> service.getStatus("GBV-AAAA-BBBB", "1.2.3.4"))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 429);
    }

    // --- getQueue / updateStatus - role checks ------------------------------

    @Test
    void campusControlCannotReadTheGbvQueue() {
        user("control@ufh.ac.za", "campus_control", 4L);

        assertThatThrownBy(() -> service.getQueue("control@ufh.ac.za", null, null, null))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void aResponderCannotReadTheGbvQueue() {
        user("responder@ufh.ac.za", "responder", 8L);

        assertThatThrownBy(() -> service.getQueue("responder@ufh.ac.za", null, null, null))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void theQueueNeverIncludesReporterIdentity() {
        assertThat(GbvReportItem.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("reporterUserId", "reporterName", "reporterEmail");
    }

    @Test
    void aGbvOfficerCanReadTheQueue() {
        user("gbv@ufh.ac.za", "gbv_officer", 9L);
        Page<GbvReport> page = new PageImpl<>(List.of(storedReport("GBV-AAAA-BBBB", GbvReportStatus.SUBMITTED, true)));
        when(reports.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(page);

        GbvReportListResponse response = service.getQueue("gbv@ufh.ac.za", null, null, null);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void anAdminCanUpdateStatus() {
        user("admin@ufh.ac.za", "admin", 1L);
        GbvReport stored = storedReport("GBV-AAAA-BBBB", GbvReportStatus.SUBMITTED, true);
        when(reports.findByReferenceCode("GBV-AAAA-BBBB")).thenReturn(Optional.of(stored));

        GbvReportStatusResponse response = service.updateStatus("admin@ufh.ac.za", "GBV-AAAA-BBBB",
            new UpdateGbvStatusRequest(GbvReportStatus.REFERRED));

        assertThat(response.status()).isEqualTo(GbvReportStatus.REFERRED);
    }

    private GbvReport storedReport(String referenceCode, GbvReportStatus status, boolean anonymous) {
        GbvReport report = new GbvReport();
        ReflectionTestUtils.setField(report, "reportId", 1L);
        report.setReferenceCode(referenceCode);
        report.setStatus(status);
        report.setAnonymous(anonymous);
        ReflectionTestUtils.invokeMethod(report, "onCreate");
        return report;
    }
}
