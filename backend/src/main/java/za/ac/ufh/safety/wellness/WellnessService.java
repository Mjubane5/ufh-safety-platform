package za.ac.ufh.safety.wellness;

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
 * Student Counselling Unit bookings and messaging. Campus Health Centre
 * bookings share the same table (resourceId 3) but have their own officer
 * role, their own queue and their own message thread - see HealthService.
 * This service owns the booking table itself and the shared
 * updateBookingStatus, since only one PATCH endpoint exists for both.
 */
@Service
public class WellnessService {

    // resourceId 3 is the Campus Health Centre - it has its own officer and
    // its own portal, so SCU's queue and messages exclude it. Matches
    // frontend/js/api.js's HEALTH_CENTRE_RESOURCE_ID. Public: HealthService
    // (a different package) reads this too, so the two never drift apart.
    public static final long HEALTH_CENTRE_RESOURCE_ID = 3L;

    static final List<WellnessResourceItem> RESOURCES = List.of(
        new WellnessResourceItem(1L, "Student Counselling Unit", "counselling",
            "On-campus professional psychological support and crisis debriefing.",
            "0406082210", "Mon-Fri 08:00-16:30"),
        new WellnessResourceItem(2L, "Peer Wellness & Support Desk", "wellness",
            "Confidential peer counselling and student wellbeing conversations.",
            "0406082215", "Tuesday and Thursday 12:00-15:00"),
        new WellnessResourceItem(HEALTH_CENTRE_RESOURCE_ID, "Campus Health Centre", "health",
            "Primary healthcare clinic and immediate medical assistance.",
            "0406082333", "Mon-Fri 08:00-17:00 (24h On-Call Nurse)")
    );

    private final WellnessBookingRepository bookings;
    private final WellnessMessageRepository messages;
    private final UserRepository users;

    public WellnessService(WellnessBookingRepository bookings, WellnessMessageRepository messages, UserRepository users) {
        this.bookings = bookings;
        this.messages = messages;
        this.users = users;
    }

    public WellnessResourcesResponse getResources() {
        return new WellnessResourcesResponse(RESOURCES);
    }

    @Transactional
    public WellnessBookingResponse createBooking(String email, CreateWellnessBookingRequest request) {
        User student = requireStudent(email);
        validateResourceAndSlot(request.resourceId(), request.preferredSlot());

        WellnessBooking booking = new WellnessBooking();
        booking.setStudentUserId(student.getUserId());
        booking.setResourceId(request.resourceId());
        booking.setPreferredDate(request.preferredDate());
        booking.setPreferredSlot(request.preferredSlot());
        booking.setNote(trimToNull(request.note()));
        booking.setStatus(WellnessBookingStatus.REQUESTED);

        WellnessBooking saved = bookings.save(booking);
        return WellnessBookingResponse.of(saved, student.getFullName());
    }

    @Transactional
    public WellnessBookingResponse updateBookingStatus(String email, Long bookingId, UpdateBookingStatusRequest request) {
        User caller = requireUser(email);
        WellnessBooking booking = bookings.findById(bookingId)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No such booking.", null));

        requireCanManageBooking(caller, booking);

        booking.setStatus(request.status());
        WellnessBooking saved = bookings.save(booking);
        User student = users.findById(saved.getStudentUserId()).orElse(null);
        return WellnessBookingResponse.of(saved, student == null ? null : student.getFullName());
    }

    /**
     * resourceId 3 belongs to health_officer, everything else to
     * scu_officer - one PATCH endpoint, split by which resource the booking
     * is actually for. admin can act on either.
     */
    void requireCanManageBooking(User caller, WellnessBooking booking) {
        boolean isHealthCentre = HEALTH_CENTRE_RESOURCE_ID == booking.getResourceId();
        boolean allowed = "admin".equals(caller.getRole())
            || (isHealthCentre && "health_officer".equals(caller.getRole()))
            || (!isHealthCentre && "scu_officer".equals(caller.getRole()));
        if (!allowed) {
            throw new ApiException(403, "FORBIDDEN", "Your role cannot manage this booking.", null);
        }
    }

    @Transactional(readOnly = true)
    public WellnessMessagesResponse getMyMessages(String email) {
        User student = requireStudent(email);
        List<WellnessMessageResponse> items = messages.findByStudentUserIdOrderBySentAtAsc(student.getUserId()).stream()
            .map(m -> WellnessMessageResponse.of(m, student.getFullName()))
            .toList();
        return new WellnessMessagesResponse(items);
    }

    @Transactional
    public WellnessMessageResponse sendMyMessage(String email, SendMessageRequest request) {
        User student = requireStudent(email);
        WellnessMessage saved = messages.save(newMessage(student.getUserId(), "student", request.text()));
        return WellnessMessageResponse.of(saved, student.getFullName());
    }

    @Transactional(readOnly = true)
    public WellnessMessagesResponse getMessagesWithStudent(String email, Long studentUserId) {
        requireScuOfficer(email);
        User student = users.findById(studentUserId).orElse(null);
        List<WellnessMessageResponse> items = messages.findByStudentUserIdOrderBySentAtAsc(studentUserId).stream()
            .map(m -> WellnessMessageResponse.of(m, student == null ? null : student.getFullName()))
            .toList();
        return new WellnessMessagesResponse(items);
    }

    @Transactional
    public WellnessMessageResponse sendMessageToStudent(String email, Long studentUserId, SendMessageRequest request) {
        requireScuOfficer(email);
        User student = users.findById(studentUserId)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No such student.", null));
        WellnessMessage saved = messages.save(newMessage(studentUserId, "scu", request.text()));
        return WellnessMessageResponse.of(saved, student.getFullName());
    }

    @Transactional(readOnly = true)
    public WellnessQueueResponse getQueue(String email) {
        requireScuOfficer(email);

        Map<Long, MutableQueueEntry> byStudent = new LinkedHashMap<>();

        for (WellnessMessage message : messages.findAll()) {
            MutableQueueEntry entry = byStudent.computeIfAbsent(message.getStudentUserId(), MutableQueueEntry::new);
            if (entry.lastActivityAt == null || message.getSentAt().isAfter(entry.lastActivityAt)) {
                entry.lastActivityAt = message.getSentAt();
            }
            if ("student".equals(message.getSender())) {
                entry.hasUnread = true;
            }
        }

        for (WellnessBooking booking : bookings.findAll()) {
            if (HEALTH_CENTRE_RESOURCE_ID == booking.getResourceId()) continue;
            MutableQueueEntry entry = byStudent.computeIfAbsent(booking.getStudentUserId(), MutableQueueEntry::new);
            if (entry.lastActivityAt == null || booking.getCreatedAt().isAfter(entry.lastActivityAt)) {
                entry.lastActivityAt = booking.getCreatedAt();
            }
            entry.bookings.add(booking);
        }

        return new WellnessQueueResponse(toSortedItems(byStudent));
    }

    List<WellnessQueueResponse.Item> toSortedItems(Map<Long, MutableQueueEntry> byStudent) {
        List<WellnessQueueResponse.Item> items = new ArrayList<>();
        for (MutableQueueEntry entry : byStudent.values()) {
            User student = users.findById(entry.studentUserId).orElse(null);
            String studentName = student == null ? null : student.getFullName();
            List<WellnessBookingResponse> bookingItems = entry.bookings.stream()
                .map(b -> WellnessBookingResponse.of(b, studentName))
                .toList();
            items.add(new WellnessQueueResponse.Item(
                entry.studentUserId, studentName, entry.lastActivityAt, entry.hasUnread, bookingItems));
        }
        items.sort(Comparator.comparing(WellnessQueueResponse.Item::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    static final class MutableQueueEntry {
        final Long studentUserId;
        Instant lastActivityAt;
        boolean hasUnread;
        final List<WellnessBooking> bookings = new ArrayList<>();

        MutableQueueEntry(Long studentUserId) {
            this.studentUserId = studentUserId;
        }
    }

    private WellnessMessage newMessage(Long studentUserId, String sender, String text) {
        WellnessMessage message = new WellnessMessage();
        message.setStudentUserId(studentUserId);
        message.setSender(sender);
        message.setText(text);
        return message;
    }

    private void validateResourceAndSlot(Long resourceId, String preferredSlot) {
        boolean knownResource = RESOURCES.stream().anyMatch(r -> r.resourceId().equals(resourceId));
        if (!knownResource) {
            throw new ApiException(400, "VALIDATION_FAILED", "Unknown resource.", "resourceId");
        }
        if (!"morning".equals(preferredSlot) && !"afternoon".equals(preferredSlot)) {
            throw new ApiException(400, "VALIDATION_FAILED", "preferredSlot must be morning or afternoon.", "preferredSlot");
        }
    }

    private User requireStudent(String email) {
        User user = requireUser(email);
        if (!"student".equals(user.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Only a student account can do this.", null);
        }
        return user;
    }

    private void requireScuOfficer(String email) {
        User user = requireUser(email);
        if (!"scu_officer".equals(user.getRole()) && !"admin".equals(user.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Your role cannot access this.", null);
        }
    }

    private User requireUser(String email) {
        return users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
