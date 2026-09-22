package za.ac.ufh.safety.healthprofile;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The fixed list of declarable conditions from docs/api-contract.md's
 * health profile section. Deliberately not free text: a responder needs a
 * quick, scannable list in an emergency, not a paragraph to read.
 */
public enum HealthCondition {

    ASTHMA("asthma"),
    DIABETES("diabetes"),
    EPILEPSY("epilepsy"),
    SEVERE_ALLERGY("severe_allergy"),
    HEART_CONDITION("heart_condition");

    private final String wireValue;

    HealthCondition(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static HealthCondition fromWireValue(String value) {
        for (HealthCondition condition : values()) {
            if (condition.wireValue.equals(value)) {
                return condition;
            }
        }
        throw new IllegalArgumentException("Unknown health condition: " + value);
    }
}
