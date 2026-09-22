package za.ac.ufh.safety.wellness;

import java.time.Instant;

public record WellnessMessageResponse(
        Long messageId,
        Long studentUserId,
        String studentName,
        String sender,
        String text,
        Instant sentAt
) {

    static WellnessMessageResponse of(WellnessMessage message, String studentName) {
        return new WellnessMessageResponse(
                message.getMessageId(),
                message.getStudentUserId(),
                studentName,
                message.getSender(),
                message.getText(),
                message.getSentAt());
    }
}
