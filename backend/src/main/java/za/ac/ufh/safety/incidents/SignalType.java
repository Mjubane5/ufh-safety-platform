package za.ac.ufh.safety.incidents;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The three derived-signal kinds distress-detection.js can send, from
 * docs/api-contract.md's "Incident live signals" section. Never raw audio
 * or video - only what the browser has already reduced to a short text or
 * label value.
 */
public enum SignalType {

    TRANSCRIPT("transcript"),
    SOUND("sound"),
    FACIAL("facial");

    private final String wireValue;

    SignalType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static SignalType fromWireValue(String value) {
        for (SignalType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown signal type: " + value);
    }
}
