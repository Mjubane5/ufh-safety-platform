package za.ac.ufh.safety.responders;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResponderServiceTest {

    private ResponderRepository responders;
    private UserRepository users;
    private ResponderService service;

    @BeforeEach
    void setUp() {
        responders = mock(ResponderRepository.class);
        users = mock(UserRepository.class);
        service = new ResponderService(responders, users);
    }

    private User user(long userId, String role, String fullName) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", userId);
        user.setEmail(userId + "@ufh.ac.za");
        user.setFullName(fullName);
        user.setRole(role);
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        return user;
    }

    private Responder responder(long id, ResponderStatus status) {
        Responder responder = new Responder();
        responder.setResponderId(id);
        responder.setTeam("campus_security");
        responder.setStatus(status);
        responder.setLatitude(-32.78210);
        responder.setLongitude(26.84800);
        return responder;
    }

    @Test
    void campusControlSeesAvailableRespondersWithTheirNames() {
        User control = user(2, "campus_control", "Control Room");
        User five = user(5, "responder", "A Responder");
        when(responders.findByStatus(ResponderStatus.AVAILABLE))
            .thenReturn(List.of(responder(5, ResponderStatus.AVAILABLE)));
        when(users.findAllById(any())).thenReturn(List.of(five));

        ResponderListResponse response = service.available(control.getEmail());

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).responderId()).isEqualTo(5L);
        assertThat(response.items().get(0).fullName()).isEqualTo("A Responder");
        assertThat(response.items().get(0).status()).isEqualTo(ResponderStatus.AVAILABLE);
    }

    // A student must not be able to read where every responder on campus is.
    @Test
    void aStudentCannotSeeResponderPositions() {
        User student = user(7, "student", "A Student");

        assertThatThrownBy(() -> service.available(student.getEmail()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Only campus control");
    }

    @Test
    void aResponderCannotSeeTheFullRoster() {
        User responder = user(5, "responder", "A Responder");

        assertThatThrownBy(() -> service.available(responder.getEmail()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Only campus control");
    }

    @Test
    void anEmptyRosterIsAnEmptyListNotAnError() {
        User control = user(2, "campus_control", "Control Room");
        when(responders.findByStatus(ResponderStatus.AVAILABLE)).thenReturn(List.of());

        ResponderListResponse response = service.available(control.getEmail());

        assertThat(response.items()).isEmpty();
    }

    // The duty row can outlive the account it points at. One nameless row is
    // better than a dispatcher seeing no responders at all.
    @Test
    void aResponderWithNoMatchingUserRowStillAppears() {
        User control = user(2, "campus_control", "Control Room");
        when(responders.findByStatus(ResponderStatus.AVAILABLE))
            .thenReturn(List.of(responder(9, ResponderStatus.AVAILABLE)));
        when(users.findAllById(any())).thenReturn(List.of());

        ResponderListResponse response = service.available(control.getEmail());

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).fullName()).isNull();
    }
}
