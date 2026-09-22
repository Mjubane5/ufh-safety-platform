package za.ac.ufh.safety.healthprofile;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * One row per student - the student's own userId is the primary key rather
 * than a generated one, since there is never more than one profile per
 * student. Declared once at registration (not built yet - see api.js's
 * comment on register()), editable any time after via this endpoint.
 *
 * conditions is stored as a comma-separated string rather than a separate
 * join table, matching this codebase's preference for plain columns over
 * JPA relationships (see Incident.reporterUserId, SafeWalk.studentUserId,
 * etc.) - the fixed, short list (HealthCondition) makes this safe: no
 * commas ever appear inside a value.
 */
@Entity
@Table(name = "health_profiles")
public class HealthProfile {

    @Id
    @Column(name = "student_user_id")
    private Long studentUserId;

    @Column(name = "conditions_csv", length = 200)
    private String conditionsCsv;

    @Column(length = 200)
    private String note;

    public Long getStudentUserId() { return studentUserId; }
    public void setStudentUserId(Long studentUserId) { this.studentUserId = studentUserId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public List<String> getConditions() {
        if (conditionsCsv == null || conditionsCsv.isBlank()) return new ArrayList<>();
        return new ArrayList<>(List.of(conditionsCsv.split(",")));
    }

    public void setConditions(List<String> conditions) {
        this.conditionsCsv = (conditions == null || conditions.isEmpty()) ? null : String.join(",", conditions);
    }
}
