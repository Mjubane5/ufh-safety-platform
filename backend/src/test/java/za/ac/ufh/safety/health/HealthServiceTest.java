package za.ac.ufh.safety.health;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;
import za.ac.ufh.safety.wellness.WellnessBooking;
import za.ac.ufh.safety.wellness.WellnessBookingRepository;
import za.ac.ufh.safety.wellness.WellnessBookingStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthServiceTest {

    private HealthMessageRepository messages;
    private WellnessBookingRepository bookings;
    private UserRepository users;
    private HealthService service;

    @BeforeEach
    void setUp() {
        messages = mock(HealthMessageRepository.class);
        bookings = mock(WellnessBookingRepository.class);
        users = mock(UserRepository.class);
        service = new HealthService(messages, bookings, users);
        when(messages.save(any())).thenAnswer(inv -> {
            HealthMessage m = inv.getArgument(0);
            if (m.getMessageId() == null) ReflectionTestUtils.setField(m, "messageId", 1L);
            return m;
        });
    }

    private User user(String email, String role, long userId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", userId);
        user.setEmail(email);
        user.setRole(role);
        user.setFullName("Test " + role);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void aStudentSendingAMessageIsStampedAsSender() {
        user("student@ufh.ac.za", "student", 17L);

        HealthMessageResponse response = service.sendMyMessage("student@ufh.ac.za", new SendMessageRequest("Hello"));

        assertThat(response.sender()).isEqualTo("student");
    }

    @Test
    void anOfficerReplyIsStampedAsHealthOfficer() {
        user("health@ufh.ac.za", "health_officer", 6L);
        user("student@ufh.ac.za", "student", 17L);

        HealthMessageResponse response = service.sendMessageToStudent("health@ufh.ac.za", 17L, new SendMessageRequest("Hi there"));

        assertThat(response.sender()).isEqualTo("health_officer");
    }

    @Test
    void anScuOfficerCannotAccessTheHealthQueue() {
        user("scu@ufh.ac.za", "scu_officer", 5L);

        assertThatThrownBy(() -> service.getQueue("scu@ufh.ac.za"))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void theHealthQueueOnlyIncludesResourceIdThreeBookings() {
        user("health@ufh.ac.za", "health_officer", 6L);
        user("student@ufh.ac.za", "student", 17L);
        when(messages.findAll()).thenReturn(List.of());
        when(bookings.findAll()).thenReturn(List.of(booking(1L, 17L), booking(3L, 17L)));

        HealthQueueResponse response = service.getQueue("health@ufh.ac.za");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).bookings()).hasSize(1)
            .allSatisfy(b -> assertThat(b.resourceId()).isEqualTo(3L));
    }

    private WellnessBooking booking(long resourceId, long studentUserId) {
        WellnessBooking booking = new WellnessBooking();
        booking.setStudentUserId(studentUserId);
        booking.setResourceId(resourceId);
        booking.setPreferredDate(LocalDate.of(2026, 8, 26));
        booking.setPreferredSlot("morning");
        booking.setStatus(WellnessBookingStatus.REQUESTED);
        ReflectionTestUtils.invokeMethod(booking, "onCreate");
        return booking;
    }
}
