package za.ac.ufh.safety.healthprofile;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

@Service
public class HealthProfileService {

    private final HealthProfileRepository profiles;
    private final UserRepository users;

    public HealthProfileService(HealthProfileRepository profiles, UserRepository users) {
        this.profiles = profiles;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public HealthProfileResponse getMyProfile(String email) {
        User student = requireStudent(email);
        return profiles.findById(student.getUserId())
            .map(HealthProfileResponse::of)
            .orElseGet(HealthProfileResponse::empty);
    }

    @Transactional
    public HealthProfileResponse updateMyProfile(String email, UpdateHealthProfileRequest request) {
        User student = requireStudent(email);
        validate(request);

        HealthProfile profile = profiles.findById(student.getUserId()).orElseGet(HealthProfile::new);
        profile.setStudentUserId(student.getUserId());
        profile.setConditions(request.conditions());
        profile.setNote(trimToNull(request.note()));

        return HealthProfileResponse.of(profiles.save(profile));
    }

    private void validate(UpdateHealthProfileRequest request) {
        for (String condition : request.conditions()) {
            try {
                HealthCondition.fromWireValue(condition);
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, "VALIDATION_FAILED",
                    "Unknown condition: " + condition, "conditions");
            }
        }
    }

    private User requireStudent(String email) {
        User user = users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED", "Sign in again.", null));
        if (!"student".equals(user.getRole())) {
            throw new ApiException(403, "FORBIDDEN", "Only a student account has a health profile.", null);
        }
        return user;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
