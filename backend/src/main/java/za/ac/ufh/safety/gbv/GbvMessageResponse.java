package za.ac.ufh.safety.gbv;

import java.time.Instant;

public record GbvMessageResponse(Long messageId, String referenceCode, String sender, String text, Instant sentAt) {

    static GbvMessageResponse of(GbvMessage message) {
        return new GbvMessageResponse(
                message.getMessageId(),
                message.getReferenceCode(),
                message.getSender(),
                message.getText(),
                message.getSentAt());
    }
}
