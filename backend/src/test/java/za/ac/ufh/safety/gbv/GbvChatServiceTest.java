package za.ac.ufh.safety.gbv;

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

class GbvChatServiceTest {

    private GbvReportRepository reports;
    private GbvMessageRepository messages;
    private UserRepository users;
    private GbvChatService chatService;

    @BeforeEach
    void setUp() {
        reports = mock(GbvReportRepository.class);
        messages = mock(GbvMessageRepository.class);
        users = mock(UserRepository.class);
        GbvStatusLookupRateLimiter rateLimiter = mock(GbvStatusLookupRateLimiter.class);
        GbvReportService reportService = new GbvReportService(reports, users, rateLimiter);
        chatService = new GbvChatService(reportService, messages);
        when(messages.save(any())).thenAnswer(inv -> {
            GbvMessage m = inv.getArgument(0);
            if (m.getMessageId() == null) ReflectionTestUtils.setField(m, "messageId", 1L);
            return m;
        });
    }

    private User user(String email, String role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "userId", 9L);
        user.setEmail(email);
        user.setRole(role);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        return user;
    }

    private GbvReport report(String referenceCode, boolean anonymous) {
        GbvReport report = new GbvReport();
        report.setReferenceCode(referenceCode);
        report.setAnonymous(anonymous);
        report.setStatus(GbvReportStatus.SUBMITTED);
        when(reports.findByReferenceCode(referenceCode)).thenReturn(Optional.of(report));
        return report;
    }

    @Test
    void anAnonymousReportHasNoChat() {
        report("GBV-AAAA-BBBB", true);

        assertThatThrownBy(() -> chatService.getMessages("GBV-AAAA-BBBB"))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void anAnonymousReportCannotReceiveAMessageEither() {
        report("GBV-AAAA-BBBB", true);

        assertThatThrownBy(() -> chatService.sendMessage("GBV-AAAA-BBBB", new GbvSendMessageRequest("Hi")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void anUnknownReferenceCodeIs404() {
        when(reports.findByReferenceCode("GBV-0000-0000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getMessages("GBV-0000-0000"))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 404);
    }

    @Test
    void aPublicMessageIsAlwaysStampedAsReporter() {
        report("GBV-AAAA-BBBB", false);

        GbvMessageResponse response = chatService.sendMessage("GBV-AAAA-BBBB", new GbvSendMessageRequest("Any update?"));

        assertThat(response.sender()).isEqualTo("reporter");
    }

    @Test
    void anOfficerReplyIsAlwaysStampedAsGbvOfficer() {
        report("GBV-AAAA-BBBB", false);
        user("gbv@ufh.ac.za", "gbv_officer");

        GbvMessageResponse response = chatService.sendMessageAsOfficer("gbv@ufh.ac.za", "GBV-AAAA-BBBB", new GbvSendMessageRequest("We're reviewing this."));

        assertThat(response.sender()).isEqualTo("gbv_officer");
    }

    @Test
    void aStudentCannotUseTheOfficerReplyEndpoint() {
        report("GBV-AAAA-BBBB", false);
        user("student@ufh.ac.za", "student");

        assertThatThrownBy(() -> chatService.sendMessageAsOfficer("student@ufh.ac.za", "GBV-AAAA-BBBB", new GbvSendMessageRequest("Hi")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", 403);
    }

    @Test
    void theChatQueueNeverIncludesAName() {
        assertThat(GbvChatQueueResponse.Item.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("referenceCode", "lastActivityAt", "hasUnread");
    }

    @Test
    void theChatQueueOnlyListsReportsWithAtLeastOneMessage() {
        user("gbv@ufh.ac.za", "gbv_officer");
        GbvMessage message = new GbvMessage();
        message.setReferenceCode("GBV-AAAA-BBBB");
        message.setSender("reporter");
        message.setText("Hi");
        ReflectionTestUtils.invokeMethod(message, "onCreate");
        when(messages.findAll()).thenReturn(List.of(message));

        GbvChatQueueResponse response = chatService.getChatQueue("gbv@ufh.ac.za");

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).referenceCode()).isEqualTo("GBV-AAAA-BBBB");
        assertThat(response.items().get(0).hasUnread()).isTrue();
    }
}
