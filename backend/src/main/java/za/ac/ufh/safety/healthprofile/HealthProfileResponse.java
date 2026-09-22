package za.ac.ufh.safety.healthprofile;

import java.util.List;

/**
 * Both empty ({conditions: [], note: null}) when nothing has been declared
 * yet - "no profile" is a normal state per the contract, never a 404.
 */
public record HealthProfileResponse(List<String> conditions, String note) {

    static HealthProfileResponse empty() {
        return new HealthProfileResponse(List.of(), null);
    }

    static HealthProfileResponse of(HealthProfile profile) {
        return new HealthProfileResponse(profile.getConditions(), profile.getNote());
    }
}
