package za.ac.ufh.safety.incidents;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Listing is scoped by role, so there is one finder per role and per
 * "filtered by status or not". Spring Data derives the query from the method
 * name; none of these are written by hand.
 */
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    // student: own reports only
    Page<Incident> findByReporterUserId(Long reporterUserId, Pageable pageable);
    Page<Incident> findByReporterUserIdAndStatus(Long reporterUserId, IncidentStatus status, Pageable pageable);

    // responder: assigned to them only
    Page<Incident> findByAssignedResponderId(Long assignedResponderId, Pageable pageable);
    Page<Incident> findByAssignedResponderIdAndStatus(Long assignedResponderId, IncidentStatus status, Pageable pageable);

    // campus_control and admin: everything
    Page<Incident> findByStatus(IncidentStatus status, Pageable pageable);
}
