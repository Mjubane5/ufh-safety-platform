package za.ac.ufh.safety.campuscontrol;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

/**
 * General (non-emergency) contact channel between a student and campus
 * control - separate from incident reporting and SOS, which already have
 * their own dedicated flow.
 */
@Service
public class CampusControlMessagingService {

    private final CampusControlMessageRepository messages;
    private final UserRepository users;

    public CampusControlMessagingService(CampusControlMessageRepository messages, UserRepository users) {
        this.messages = messages;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public CampusControlMessagesResponse getMyMessages(String email) {
        User student = requireStudent(email);
        List<CampusControlMessageResponse> items = messages.findByStudentUserIdOrderBySentAtAsc(student.getUserId()).stream()
            .map(m -> CampusControlMessageResponse.of(m, student.getFullName()))
            .toList();
        return new CampusControlMessagesResponse(items);
    }

    @Transactional
    public CampusControlMessageResponse sendMyMessage(String email, SendMessageRequest request) {
        User student = requireStudent(email);
        CampusControlMessage saved = messages.save(newMessage(student.getUserId(), "student", request.text()));
        return CampusControlMessageResponse.of(saved, student.getFullName());
    }

    @Transactional(readOnly = true)
    public CampusControlMessagesResponse getMessagesWithStudent(String email, Long studentUserId) {
        requireCampusControl(email);
        User student = users.findById(studentUserId).orElse(null);
        List<CampusControlMessageResponse> items = messages.findByStudentUserIdOrderBySentAtAsc(studentUserId).stream()
            .map(m -> CampusControlMessageResponse.of(m, student == null ? null : student.getFullName()))
            .toList();
        return new CampusControlMessagesResponse(items);
    }

    @Transactional
    public CampusControlMessageResponse sendMessageToStudent(String email, Long studentUserId, SendMessageRequest request) {
        requireCampusControl(email);
        User student = users.findById(studentUserId)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No such student.", null));
        CampusControlMessage saved = messages.save(newMessage(studentUserId, "campus_control", request.text()));
        return CampusControlMessageResponse.of(saved, student.getFullName());
    }

    @Transactional(readOnly = true)
    public CampusControlQueueResponse getQueue(String email) {
        requireCampusControl(email);

        // Same rule as WellnessService/HealthService's queues: hasUnread is
        // true the moment any message from the student has been seen, and
        // stays true across the rest of the thread - matches the mock
        // behaviour this replaces.
        Map<Long, MutableEntry> byStudent = new LinkedHashMap<>();
        for (CampusControlMessage message : messages.findAll()) {
            MutableEntry entry = byStudent.computeIfAbsent(message.getStudentUserId(), MutableEntry::new);
            if (entry.lastActivityAt == null || message.getSentAt().isAfter(entry.lastActivityAt)) {
                entry.lastActivityAt = message.getSentAt();
            }
            if ("student".equals(message.getSender())) {
                entry.hasUnread = true;
            }
        }

        List<CampusControlQueueResponse.Item> items = new ArrayList<>();
        for (MutableEntry entry : byStudent.values()) {
            User student = users.findById(entry.studentUserId).orElse(null);
            items.add(new CampusControlQueueResponse.Item(
                entry.studentUserId, student == null ? null : student.getFullName(), entry.lastActivityAt, entry.hasUnread));
        }
        items.sort(Comparator.comparing(CampusControlQueueResponse.Item::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return new CampusControlQueueResponse(items);
    }

    private static final class MutableEntry {
        final Long studentUserId;
        Instant lastActivityAt;
        boolean hasUnread;

        MutableEntry(Long studentUserId) {
            this.studentUserId = studentUserId;
        }
    }

    private CampusControlMessage newMessage(Long studentUserId, String sender, String text) {
        CampusControlMessage message = new CampusControlMessage();
        message.setStudentUserId(studentUserId);
        message.setSender(sender);
        message.setText(text);
        return message;
    }

    private User requireStudent(String email) {
        User user = requireUser(email);
        if (!"student".equals(user.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Only a student account can do this.", null);
        }
        return user;
    }

    private void requireCampusControl(String email) {
        User user = requireUser(email);
        if (!"campus_control".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Your role cannot access this.", null);
        }
    }

    private User requireUser(String email) {
        return users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));
    }
}
