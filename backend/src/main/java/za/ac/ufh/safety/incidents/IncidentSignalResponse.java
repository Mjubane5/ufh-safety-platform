package za.ac.ufh.safety.incidents;

import java.time.Instant;

public record IncidentSignalResponse(
        Long signalId,
        Long incidentId,
        SignalType type,
        String label,
        Double confidence,
        String text,
        Instant createdAt
) {

    static IncidentSignalResponse of(IncidentSignal signal) {
        return new IncidentSignalResponse(
                signal.getSignalId(),
                signal.getIncidentId(),
                signal.getType(),
                signal.getLabel(),
                signal.getConfidence(),
                signal.getText(),
                signal.getCreatedAt());
    }
}
