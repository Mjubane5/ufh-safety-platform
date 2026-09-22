package za.ac.ufh.safety.campuscontrol;

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

class CampusControlMessagingServiceTest {

    private CampusControlMessageRepository messages;
    private UserRepository users;
    private CampusControlMessagingService service;

    @BeforeEach
    void setUp() {
        messages = mock(CampusControlMessageRepository.class);
        users = mock(UserRepository.class);
        service = new CampusControlMessagingService(messages, users);
        when(messages.save(any())).thenAnswer(inv -> {
            CampusControlMessage m = inv.getArgument(0);
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
    void aResponderCannotAccessTheContactQueue() {
        user("responder@ufh.ac.za", "responder", 8L);

        assertThatThrownBy(() -> service.getQueue("responder@ufh.ac.za"))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void aStudentSendingAMessageIsStampedAsSender() {
        user("student@ufh.ac.za", "student", 17L);

        CampusControlMessageResponse response = service.sendMyMessage("student@ufh.ac.za", new SendMessageRequest("Where is lost property?"));

        assertThat(response.sender()).isEqualTo("student");
    }

    @Test
    void anOfficerReplyIsStampedAsCampusControl() {
        user("control@ufh.ac.za", "campus_control", 4L);
        user("student@ufh.ac.za", "student", 17L);

        CampusControlMessageResponse response = service.sendMessageToStudent("control@ufh.ac.za", 17L, new SendMessageRequest("Third floor, Admin building."));

        assertThat(response.sender()).isEqualTo("campus_control");
    }

    @Test
    void hasUnreadStaysTrueAfterAnOfficerReply() {
        user("control@ufh.ac.za", "campus_control", 4L);
        user("student@ufh.ac.za", "student", 17L);
        when(messages.findAll()).thenReturn(List.of(
            message(17L, "student", java.time.Instant.parse("2026-09-21T09:00:00Z")),
            message(17L, "campus_control", java.time.Instant.parse("2026-09-21T09:05:00Z"))
        ));

        CampusControlQueueResponse response = service.getQueue("control@ufh.ac.za");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).hasUnread()).isTrue();
    }

    private CampusControlMessage message(long studentUserId, String sender, java.time.Instant sentAt) {
        CampusControlMessage message = new CampusControlMessage();
        message.setStudentUserId(studentUserId);
        message.setSender(sender);
        message.setText("text");
        ReflectionTestUtils.setField(message, "sentAt", sentAt);
        return message;
    }
}
