package za.ac.ufh.safety.health;

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
import za.ac.ufh.safety.wellness.WellnessBooking;
import za.ac.ufh.safety.wellness.WellnessBookingRepository;
import za.ac.ufh.safety.wellness.WellnessBookingResponse;
import za.ac.ufh.safety.wellness.WellnessService;

/**
 * Campus Health Centre messaging and booking queue. Bookings themselves
 * live in WellnessBookingRepository (resourceId 3) - updating one still
 * goes through WellnessController's PATCH /wellness/bookings/{id}, which
 * already checks for health_officer on a resourceId-3 row. This service
 * only reads bookings, and owns HealthMessage, which is entirely separate
 * from WellnessMessage.
 */
@Service
public class HealthService {

    private final HealthMessageRepository messages;
    private final WellnessBookingRepository bookings;
    private final UserRepository users;

    public HealthService(HealthMessageRepository messages, WellnessBookingRepository bookings, UserRepository users) {
        this.messages = messages;
        this.bookings = bookings;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public HealthMessagesResponse getMyMessages(String email) {
        User student = requireStudent(email);
        List<HealthMessageResponse> items = messages.findByStudentUserIdOrderBySentAtAsc(student.getUserId()).stream()
            .map(m -> HealthMessageResponse.of(m, student.getFullName()))
            .toList();
        return new HealthMessagesResponse(items);
    }

    @Transactional
    public HealthMessageResponse sendMyMessage(String email, SendMessageRequest request) {
        User student = requireStudent(email);
        HealthMessage saved = messages.save(newMessage(student.getUserId(), "student", request.text()));
        return HealthMessageResponse.of(saved, student.getFullName());
    }

    @Transactional(readOnly = true)
    public HealthMessagesResponse getMessagesWithStudent(String email, Long studentUserId) {
        requireHealthOfficer(email);
        User student = users.findById(studentUserId).orElse(null);
        List<HealthMessageResponse> items = messages.findByStudentUserIdOrderBySentAtAsc(studentUserId).stream()
            .map(m -> HealthMessageResponse.of(m, student == null ? null : student.getFullName()))
            .toList();
        return new HealthMessagesResponse(items);
    }

    @Transactional
    public HealthMessageResponse sendMessageToStudent(String email, Long studentUserId, SendMessageRequest request) {
        requireHealthOfficer(email);
        User student = users.findById(studentUserId)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No such student.", null));
        HealthMessage saved = messages.save(newMessage(studentUserId, "health_officer", request.text()));
        return HealthMessageResponse.of(saved, student.getFullName());
    }

    @Transactional(readOnly = true)
    public HealthQueueResponse getQueue(String email) {
        requireHealthOfficer(email);

        Map<Long, MutableQueueEntry> byStudent = new LinkedHashMap<>();

        for (HealthMessage message : messages.findAll()) {
            MutableQueueEntry entry = byStudent.computeIfAbsent(message.getStudentUserId(), MutableQueueEntry::new);
            if (entry.lastActivityAt == null || message.getSentAt().isAfter(entry.lastActivityAt)) {
                entry.lastActivityAt = message.getSentAt();
            }
            if ("student".equals(message.getSender())) {
                entry.hasUnread = true;
            }
        }

        for (WellnessBooking booking : bookings.findAll()) {
            if (WellnessService.HEALTH_CENTRE_RESOURCE_ID != booking.getResourceId()) continue;
            MutableQueueEntry entry = byStudent.computeIfAbsent(booking.getStudentUserId(), MutableQueueEntry::new);
            if (entry.lastActivityAt == null || booking.getCreatedAt().isAfter(entry.lastActivityAt)) {
                entry.lastActivityAt = booking.getCreatedAt();
            }
            entry.bookings.add(booking);
        }

        List<HealthQueueResponse.Item> items = new ArrayList<>();
        for (MutableQueueEntry entry : byStudent.values()) {
            User student = users.findById(entry.studentUserId).orElse(null);
            String studentName = student == null ? null : student.getFullName();
            List<WellnessBookingResponse> bookingItems = entry.bookings.stream()
                .map(b -> WellnessBookingResponse.of(b, studentName))
                .toList();
            items.add(new HealthQueueResponse.Item(
                entry.studentUserId, studentName, entry.lastActivityAt, entry.hasUnread, bookingItems));
        }
        items.sort(Comparator.comparing(HealthQueueResponse.Item::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder())));

        return new HealthQueueResponse(items);
    }

    private static final class MutableQueueEntry {
        final Long studentUserId;
        Instant lastActivityAt;
        boolean hasUnread;
        final List<WellnessBooking> bookings = new ArrayList<>();

        MutableQueueEntry(Long studentUserId) {
            this.studentUserId = studentUserId;
        }
    }

    private HealthMessage newMessage(Long studentUserId, String sender, String text) {
        HealthMessage message = new HealthMessage();
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

    private void requireHealthOfficer(String email) {
        User user = requireUser(email);
        if (!"health_officer".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Your role cannot access this.", null);
        }
    }

    private User requireUser(String email) {
        return users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));
    }
}
