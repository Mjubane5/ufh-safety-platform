package za.ac.ufh.safety.wellness;

import java.time.LocalDate;
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

class WellnessServiceTest {

    private WellnessBookingRepository bookings;
    private WellnessMessageRepository messages;
    private UserRepository users;
    private WellnessService service;

    @BeforeEach
    void setUp() {
        bookings = mock(WellnessBookingRepository.class);
        messages = mock(WellnessMessageRepository.class);
        users = mock(UserRepository.class);
        service = new WellnessService(bookings, messages, users);
        when(bookings.save(any())).thenAnswer(inv -> {
            WellnessBooking b = inv.getArgument(0);
            if (b.getBookingId() == null) ReflectionTestUtils.setField(b, "bookingId", 31L);
            return b;
        });
        when(messages.save(any())).thenAnswer(inv -> {
            WellnessMessage m = inv.getArgument(0);
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

    // --- getResources ---------------------------------------------------

    @Test
    void resourcesIncludeTheHealthCentreAtResourceIdThree() {
        WellnessResourcesResponse response = service.getResources();

        assertThat(response.items()).extracting(WellnessResourceItem::resourceId)
            .containsExactly(1L, 2L, 3L);
    }

    // --- createBooking ----------------------------------------------------

    @Test
    void aStudentCanBookAKnownResource() {
        user("student@ufh.ac.za", "student", 17L);

        WellnessBookingResponse response = service.createBooking("student@ufh.ac.za",
            new CreateWellnessBookingRequest(1L, LocalDate.of(2026, 8, 26), "morning", null));

        assertThat(response.bookingId()).isEqualTo(31L);
        assertThat(response.status()).isEqualTo(WellnessBookingStatus.REQUESTED);
    }

    @Test
    void anScuOfficerCannotBookOnTheirOwnBehalf() {
        user("scu@ufh.ac.za", "scu_officer", 5L);

        assertThatThrownBy(() -> service.createBooking("scu@ufh.ac.za",
            new CreateWellnessBookingRequest(1L, LocalDate.of(2026, 8, 26), "morning", null)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void anUnknownResourceIdIsRejected() {
        user("student@ufh.ac.za", "student", 17L);

        assertThatThrownBy(() -> service.createBooking("student@ufh.ac.za",
            new CreateWellnessBookingRequest(999L, LocalDate.of(2026, 8, 26), "morning", null)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("field", "resourceId");
    }

    @Test
    void anInvalidPreferredSlotIsRejected() {
        user("student@ufh.ac.za", "student", 17L);

        assertThatThrownBy(() -> service.createBooking("student@ufh.ac.za",
            new CreateWellnessBookingRequest(1L, LocalDate.of(2026, 8, 26), "midnight", null)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("field", "preferredSlot");
    }

    @Test
    void aBlankNoteIsStoredAsNull() {
        user("student@ufh.ac.za", "student", 17L);

        WellnessBookingResponse response = service.createBooking("student@ufh.ac.za",
            new CreateWellnessBookingRequest(1L, LocalDate.of(2026, 8, 26), "morning", "   "));

        assertThat(response.note()).isNull();
    }

    // --- updateBookingStatus: the resourceId-3 split -----------------------

    private WellnessBooking storedBooking(long bookingId, long resourceId, long studentUserId) {
        WellnessBooking booking = new WellnessBooking();
        ReflectionTestUtils.setField(booking, "bookingId", bookingId);
        booking.setStudentUserId(studentUserId);
        booking.setResourceId(resourceId);
        booking.setPreferredDate(LocalDate.of(2026, 8, 26));
        booking.setPreferredSlot("morning");
        booking.setStatus(WellnessBookingStatus.REQUESTED);
        when(bookings.findById(bookingId)).thenReturn(Optional.of(booking));
        return booking;
    }

    @Test
    void scuOfficerCanConfirmAResourceOneBooking() {
        user("scu@ufh.ac.za", "scu_officer", 5L);
        storedBooking(31L, 1L, 17L);

        WellnessBookingResponse response = service.updateBookingStatus("scu@ufh.ac.za", 31L,
            new UpdateBookingStatusRequest(WellnessBookingStatus.CONFIRMED));

        assertThat(response.status()).isEqualTo(WellnessBookingStatus.CONFIRMED);
    }

    @Test
    void scuOfficerCannotManageAHealthCentreBooking() {
        user("scu@ufh.ac.za", "scu_officer", 5L);
        storedBooking(31L, 3L, 17L);

        assertThatThrownBy(() -> service.updateBookingStatus("scu@ufh.ac.za", 31L,
            new UpdateBookingStatusRequest(WellnessBookingStatus.CONFIRMED)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void healthOfficerCanManageAHealthCentreBooking() {
        user("health@ufh.ac.za", "health_officer", 6L);
        storedBooking(31L, 3L, 17L);

        WellnessBookingResponse response = service.updateBookingStatus("health@ufh.ac.za", 31L,
            new UpdateBookingStatusRequest(WellnessBookingStatus.CONFIRMED));

        assertThat(response.status()).isEqualTo(WellnessBookingStatus.CONFIRMED);
    }

    @Test
    void healthOfficerCannotManageAScuBooking() {
        user("health@ufh.ac.za", "health_officer", 6L);
        storedBooking(31L, 1L, 17L);

        assertThatThrownBy(() -> service.updateBookingStatus("health@ufh.ac.za", 31L,
            new UpdateBookingStatusRequest(WellnessBookingStatus.CONFIRMED)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void anUnknownBookingIdIs404() {
        user("scu@ufh.ac.za", "scu_officer", 5L);
        when(bookings.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateBookingStatus("scu@ufh.ac.za", 999L,
            new UpdateBookingStatusRequest(WellnessBookingStatus.CONFIRMED)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 404);
    }

    // --- messages -----------------------------------------------------------

    @Test
    void aStudentSendingAMessageIsStampedAsSender() {
        user("student@ufh.ac.za", "student", 17L);

        WellnessMessageResponse response = service.sendMyMessage("student@ufh.ac.za", new SendMessageRequest("Hello"));

        assertThat(response.sender()).isEqualTo("student");
    }

    @Test
    void anOfficerReplyIsStampedAsScu() {
        user("scu@ufh.ac.za", "scu_officer", 5L);
        user("student@ufh.ac.za", "student", 17L);

        WellnessMessageResponse response = service.sendMessageToStudent("scu@ufh.ac.za", 17L, new SendMessageRequest("Hi there"));

        assertThat(response.sender()).isEqualTo("scu");
    }

    @Test
    void aStudentCannotReadTheOfficerOnlyPerStudentEndpoint() {
        user("student@ufh.ac.za", "student", 17L);

        assertThatThrownBy(() -> service.getMessagesWithStudent("student@ufh.ac.za", 17L))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    // --- getQueue -------------------------------------------------------------

    @Test
    void theScuQueueExcludesHealthCentreBookings() {
        user("scu@ufh.ac.za", "scu_officer", 5L);
        user("student@ufh.ac.za", "student", 17L);
        when(messages.findAll()).thenReturn(List.of());
        when(bookings.findAll()).thenReturn(List.of(
            storedBookingUnsaved(1L, 17L),   // scu resource
            storedBookingUnsaved(3L, 17L)    // health centre resource - must be excluded
        ));

        WellnessQueueResponse response = service.getQueue("scu@ufh.ac.za");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).bookings()).hasSize(1)
            .allSatisfy(b -> assertThat(b.resourceId()).isEqualTo(1L));
    }

    private WellnessBooking storedBookingUnsaved(long resourceId, long studentUserId) {
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
