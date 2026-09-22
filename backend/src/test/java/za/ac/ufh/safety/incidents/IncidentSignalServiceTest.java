package za.ac.ufh.safety.incidents;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncidentSignalServiceTest {

    private IncidentSignalRepository signals;
    private IncidentRepository incidents;
    private UserRepository users;
    private IncidentService incidentService;
    private IncidentSignalService service;

    @BeforeEach
    void setUp() {
        signals = mock(IncidentSignalRepository.class);
        incidents = mock(IncidentRepository.class);
        users = mock(UserRepository.class);
        ResponderRepository responders = mock(ResponderRepository.class);
        incidentService = new IncidentService(incidents, users, responders, new PriorityCalculator());
        service = new IncidentSignalService(signals, incidentService, users);
        when(signals.save(any())).thenAnswer(inv -> {
            IncidentSignal s = inv.getArgument(0);
            if (s.getSignalId() == null) ReflectionTestUtils.setField(s, "signalId", 1L);
            return s;
        });
    }

    private User user(String email, String role, long userId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", userId);
        user.setEmail(email);
        user.setRole(role);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        return user;
    }

    private Incident incident(long incidentId, long reporterUserId, IncidentStatus status) {
        Incident incident = new Incident();
        ReflectionTestUtils.setField(incident, "incidentId", incidentId);
        incident.setReporterUserId(reporterUserId);
        incident.setType(IncidentType.SOS);
        incident.setStatus(status);
        incident.setAnonymous(false);
        when(incidents.findById(incidentId)).thenReturn(Optional.of(incident));
        return incident;
    }

    // --- append: only the reporter, on their own device --------------------

    @Test
    void theReporterCanAppendASignalToTheirOwnIncident() {
        user("student@ufh.ac.za", "student", 17L);
        incident(42L, 17L, IncidentStatus.REPORTED);

        IncidentSignalResponse response = service.append("student@ufh.ac.za", 42L,
            new AppendSignalRequest(SignalType.TRANSCRIPT, null, null, "Someone help me"));

        assertThat(response.text()).isEqualTo("Someone help me");
        assertThat(response.incidentId()).isEqualTo(42L);
    }

    @Test
    void aDifferentStudentGets404NotTheirIncident() {
        user("other@ufh.ac.za", "student", 99L);
        incident(42L, 17L, IncidentStatus.REPORTED);

        assertThatThrownBy(() -> service.append("other@ufh.ac.za", 42L,
            new AppendSignalRequest(SignalType.SOUND, "Screaming", 0.4, null)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 404);
    }

    @Test
    void campusControlCanViewButCannotAppendASignal() {
        user("control@ufh.ac.za", "campus_control", 4L);
        incident(42L, 17L, IncidentStatus.REPORTED);

        assertThatThrownBy(() -> service.append("control@ufh.ac.za", 42L,
            new AppendSignalRequest(SignalType.FACIAL, "fearful", 0.7, null)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void theAssignedResponderCannotAppendASignalEitherOnlyTheReporterCan() {
        user("responder@ufh.ac.za", "responder", 5L);
        Incident incident = incident(42L, 17L, IncidentStatus.ASSIGNED);
        incident.setAssignedResponderId(5L);

        assertThatThrownBy(() -> service.append("responder@ufh.ac.za", 42L,
            new AppendSignalRequest(SignalType.SOUND, "Screaming", 0.4, null)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void aResolvedIncidentRefusesNewSignals() {
        user("student@ufh.ac.za", "student", 17L);
        incident(42L, 17L, IncidentStatus.RESOLVED);

        assertThatThrownBy(() -> service.append("student@ufh.ac.za", 42L,
            new AppendSignalRequest(SignalType.TRANSCRIPT, null, null, "still there?")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 409);
    }

    @Test
    void aCancelledIncidentRefusesNewSignalsToo() {
        user("student@ufh.ac.za", "student", 17L);
        incident(42L, 17L, IncidentStatus.CANCELLED);

        assertThatThrownBy(() -> service.append("student@ufh.ac.za", 42L,
            new AppendSignalRequest(SignalType.TRANSCRIPT, null, null, "still there?")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 409);
    }

    // --- list: same visibility as the incident itself -----------------------

    @Test
    void theReporterCanListTheirOwnSignals() {
        user("student@ufh.ac.za", "student", 17L);
        incident(42L, 17L, IncidentStatus.REPORTED);
        when(signals.findByIncidentIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        IncidentSignalsResponse response = service.list("student@ufh.ac.za", 42L);

        assertThat(response.items()).isEmpty();
    }

    @Test
    void anUnrelatedStudentCannotListSignalsEither() {
        user("other@ufh.ac.za", "student", 99L);
        incident(42L, 17L, IncidentStatus.REPORTED);

        assertThatThrownBy(() -> service.list("other@ufh.ac.za", 42L))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 404);
    }

    @Test
    void campusControlCanListSignalsForAnyIncident() {
        user("control@ufh.ac.za", "campus_control", 4L);
        incident(42L, 17L, IncidentStatus.REPORTED);
        when(signals.findByIncidentIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        IncidentSignalsResponse response = service.list("control@ufh.ac.za", 42L);

        assertThat(response.items()).isEmpty();
    }
}
