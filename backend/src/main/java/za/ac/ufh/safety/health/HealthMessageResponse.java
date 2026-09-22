package za.ac.ufh.safety.health;

import java.time.Instant;

public record HealthMessageResponse(
        Long messageId,
        Long studentUserId,
        String studentName,
        String sender,
        String text,
        Instant sentAt
) {

    static HealthMessageResponse of(HealthMessage message, String studentName) {
        return new HealthMessageResponse(
                message.getMessageId(),
                message.getStudentUserId(),
                studentName,
                message.getSender(),
                message.getText(),
                message.getSentAt());
    }
}
