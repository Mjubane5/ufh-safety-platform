package za.ac.ufh.safety.campuscontrol;

import java.time.Instant;

public record CampusControlMessageResponse(
        Long messageId,
        Long studentUserId,
        String studentName,
        String sender,
        String text,
        Instant sentAt
) {

    static CampusControlMessageResponse of(CampusControlMessage message, String studentName) {
        return new CampusControlMessageResponse(
                message.getMessageId(),
                message.getStudentUserId(),
                studentName,
                message.getSender(),
                message.getText(),
                message.getSentAt());
    }
}
