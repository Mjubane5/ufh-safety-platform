package za.ac.ufh.safety.incidents;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.responders.Responder;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.responders.ResponderStatus;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The specification for IncidentAssignmentService, written before the code.
 *
 * These were written @Disabled, one annotation removed at a time. They all
 * run now, so any change to the service that breaks one of these rules fails
 * the build rather than being found during a demonstration.
 */
class IncidentAssignmentServiceTest {

    private static final double LIBRARY_LAT = -32.78331;
    private static final double LIBRARY_LON = 26.84971;

    private IncidentRepository incidents;
    private ResponderRepository responders;
    private UserRepository users;
    private IncidentAssignmentService service;

    @BeforeEach
    void setUp() {
        incidents = mock(IncidentRepository.class);
        responders = mock(ResponderRepository.class);
        users = mock(UserRepository.class);
        service = new IncidentAssignmentService(incidents, responders, users);
        when(incidents.save(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
        when(responders.save(any(Responder.class))).thenAnswer(call -> call.getArgument(0));
    }

    private User user(long userId, String role, String fullName) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", userId);
        user.setEmail(userId + "@ufh.ac.za");
        user.setFullName(fullName);
        user.setRole(role);
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        return user;
    }

    /** An incident at the library, with coordinates. */
    private Incident locatedIncident(long incidentId) {
        Incident incident = new Incident();
        ReflectionTestUtils.setField(incident, "incidentId", incidentId);
        incident.setReporterUserId(7L);
        incident.setType(IncidentType.MEDICAL);
        incident.setStatus(IncidentStatus.REPORTED);
        incident.setPriority(2);
        incident.setAnonymous(false);
        incident.setLatitude(LIBRARY_LAT);
        incident.setLongitude(LIBRARY_LON);
        ReflectionTestUtils.setField(incident, "locationSource", "device");
        when(incidents.findById(incidentId)).thenReturn(Optional.of(incident));
        return incident;
    }

    /** An incident the student could not attach a position to. */
    private Incident unlocatedIncident(long incidentId) {
        Incident incident = locatedIncident(incidentId);
        incident.setLatitude(null);
        incident.setLongitude(null);
        ReflectionTestUtils.setField(incident, "locationSource", "none");
        return incident;
    }

    private Responder responder(long id, ResponderStatus status, Double lat, Double lon) {
        Responder responder = new Responder();
        responder.setResponderId(id);
        responder.setTeam("campus_security");
        responder.setStatus(status);
        responder.setLatitude(lat);
        responder.setLongitude(lon);
        when(responders.findById(id)).thenReturn(Optional.of(responder));
        return responder;
    }

    // -----------------------------------------------------------------------
    // Who may dispatch
    // -----------------------------------------------------------------------

    @Test
    void aStudentCannotAssignAnybody() {
        User student = user(7, "student", "A Student");
        locatedIncident(42);

        assertThatThrownBy(() -> service.assign(student.getEmail(), 42L,
            new AssignIncidentRequest(5L)))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void aResponderCannotAssignThemselves() {
        User responderUser = user(5, "responder", "A Responder");
        locatedIncident(42);

        assertThatThrownBy(() -> service.assign(responderUser.getEmail(), 42L,
            new AssignIncidentRequest(5L)))
            .isInstanceOf(ApiException.class);
    }

    // -----------------------------------------------------------------------
    // Assigning by hand
    // -----------------------------------------------------------------------

    @Test
    void campusControlCanAssignANamedResponder() {
        User control = user(2, "campus_control", "Control Room");
        user(5, "responder", "Nomsa Khumalo");
        locatedIncident(42);
        responder(5, ResponderStatus.AVAILABLE, -32.78210, 26.84800);

        AssignmentResponse response = service.assign(control.getEmail(), 42L,
            new AssignIncidentRequest(5L));

        assertThat(response.status()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(response.responder().responderId()).isEqualTo(5L);
        assertThat(response.responder().fullName()).isEqualTo("Nomsa Khumalo");
    }

    // All three writes matter. Miss the third and the same person is picked
    // for every incident on campus.
    @Test
    void assigningMarksTheIncidentAndTakesTheResponderOutOfThePool() {
        User control = user(2, "campus_control", "Control Room");
        user(5, "responder", "Nomsa Khumalo");
        Incident incident = locatedIncident(42);
        Responder chosen = responder(5, ResponderStatus.AVAILABLE, -32.78210, 26.84800);

        service.assign(control.getEmail(), 42L, new AssignIncidentRequest(5L));

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(incident.getAssignedResponderId()).isEqualTo(5L);
        assertThat(chosen.getStatus()).isEqualTo(ResponderStatus.ASSIGNED);
    }

    @Test
    void anIncidentThatIsAlreadyAssignedIsRefused() {
        User control = user(2, "campus_control", "Control Room");
        Incident incident = locatedIncident(42);
        incident.setStatus(IncidentStatus.ASSIGNED);
        incident.setAssignedResponderId(6L);
        responder(5, ResponderStatus.AVAILABLE, -32.78210, 26.84800);

        assertThatThrownBy(() -> service.assign(control.getEmail(), 42L,
            new AssignIncidentRequest(5L)))
            .isInstanceOf(ApiException.class);
    }

    // -----------------------------------------------------------------------
    // Letting the backend choose
    // -----------------------------------------------------------------------

    @Test
    void autoAssignmentPicksTheNearestAvailableResponder() {
        User control = user(2, "campus_control", "Control Room");
        user(5, "responder", "Near Responder");
        user(6, "responder", "Far Responder");
        locatedIncident(42);

        Responder near = responder(5, ResponderStatus.AVAILABLE, -32.78340, 26.84980);
        Responder far = responder(6, ResponderStatus.AVAILABLE, -32.79500, 26.86000);
        when(responders.findByStatus(ResponderStatus.AVAILABLE)).thenReturn(List.of(far, near));

        AssignmentResponse response = service.assign(control.getEmail(), 42L,
            new AssignIncidentRequest(null));

        assertThat(response.responder().responderId()).isEqualTo(5L);
    }

    @Test
    void autoAssignmentWithNobodyAvailableIsRefused() {
        User control = user(2, "campus_control", "Control Room");
        locatedIncident(42);
        when(responders.findByStatus(ResponderStatus.AVAILABLE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.assign(control.getEmail(), 42L,
            new AssignIncidentRequest(null)))
            .isInstanceOf(ApiException.class);
    }

    // -----------------------------------------------------------------------
    // The pair that looks contradictory and is not
    // -----------------------------------------------------------------------

    /**
     * No coordinates means no origin to measure from, so "nearest" has no
     * meaning. Refusing is the correct answer — picking anyway would send
     * help to the wrong place while the screen looked right.
     */
    @Test
    void autoAssignmentIsRefusedWhenTheIncidentHasNoLocation() {
        User control = user(2, "campus_control", "Control Room");
        unlocatedIncident(42);

        // Built on its own line, not inside the when(...) below. The helper
        // stubs findById, and Mockito rejects a stub that starts while
        // another one is still open.
        Responder available = responder(5, ResponderStatus.AVAILABLE, -32.78210, 26.84800);
        when(responders.findByStatus(ResponderStatus.AVAILABLE)).thenReturn(List.of(available));

        assertThatThrownBy(() -> service.assign(control.getEmail(), 42L,
            new AssignIncidentRequest(null)))
            .isInstanceOf(ApiException.class);
    }

    /**
     * The same incident, assigned by hand, succeeds. A dispatcher who has read
     * the description may know roughly where the student is. The difference
     * from the test above is that a human made the decision.
     *
     * The route is null: there is still no destination to route to.
     */
    @Test
    void anExplicitResponderIsAcceptedEvenWithNoLocationButCarriesNoRoute() {
        User control = user(2, "campus_control", "Control Room");
        user(5, "responder", "Nomsa Khumalo");
        unlocatedIncident(42);
        responder(5, ResponderStatus.AVAILABLE, -32.78210, 26.84800);

        AssignmentResponse response = service.assign(control.getEmail(), 42L,
            new AssignIncidentRequest(5L));

        assertThat(response.status()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(response.responder().responderId()).isEqualTo(5L);
        assertThat(response.route()).isNull();
    }
}
