package za.ac.ufh.safety.healthprofile;

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

class HealthProfileServiceTest {

    private HealthProfileRepository profiles;
    private UserRepository users;
    private HealthProfileService service;

    @BeforeEach
    void setUp() {
        profiles = mock(HealthProfileRepository.class);
        users = mock(UserRepository.class);
        service = new HealthProfileService(profiles, users);
        when(profiles.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private User user(String email, String role, long userId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", userId);
        user.setEmail(email);
        user.setRole(role);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void noProfileYetIsEmptyNotAnError() {
        user("student@ufh.ac.za", "student", 17L);
        when(profiles.findById(17L)).thenReturn(Optional.empty());

        HealthProfileResponse response = service.getMyProfile("student@ufh.ac.za");

        assertThat(response.conditions()).isEmpty();
        assertThat(response.note()).isNull();
    }

    @Test
    void aResponderHasNoHealthProfile() {
        user("responder@ufh.ac.za", "responder", 8L);

        assertThatThrownBy(() -> service.getMyProfile("responder@ufh.ac.za"))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void savingAKnownConditionListRoundTrips() {
        user("student@ufh.ac.za", "student", 17L);
        when(profiles.findById(17L)).thenReturn(Optional.empty());

        HealthProfileResponse response = service.updateMyProfile("student@ufh.ac.za",
            new UpdateHealthProfileRequest(List.of("asthma", "severe_allergy"), "Carries an EpiPen"));

        assertThat(response.conditions()).containsExactly("asthma", "severe_allergy");
        assertThat(response.note()).isEqualTo("Carries an EpiPen");
    }

    @Test
    void anUnknownConditionIsRejected() {
        user("student@ufh.ac.za", "student", 17L);

        assertThatThrownBy(() -> service.updateMyProfile("student@ufh.ac.za",
            new UpdateHealthProfileRequest(List.of("flu"), null)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("field", "conditions");
    }

    @Test
    void aBlankNoteIsStoredAsNull() {
        user("student@ufh.ac.za", "student", 17L);
        when(profiles.findById(17L)).thenReturn(Optional.empty());

        HealthProfileResponse response = service.updateMyProfile("student@ufh.ac.za",
            new UpdateHealthProfileRequest(List.of(), "   "));

        assertThat(response.note()).isNull();
    }

    @Test
    void updatingAnExistingProfileReusesTheSameRow() {
        user("student@ufh.ac.za", "student", 17L);
        HealthProfile existing = new HealthProfile();
        existing.setStudentUserId(17L);
        existing.setConditions(List.of("diabetes"));
        when(profiles.findById(17L)).thenReturn(Optional.of(existing));

        HealthProfileResponse response = service.updateMyProfile("student@ufh.ac.za",
            new UpdateHealthProfileRequest(List.of("epilepsy"), "Updated"));

        assertThat(response.conditions()).containsExactly("epilepsy");
    }
}
