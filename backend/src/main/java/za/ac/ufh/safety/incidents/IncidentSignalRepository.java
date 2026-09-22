package za.ac.ufh.safety.incidents;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentSignalRepository extends JpaRepository<IncidentSignal, Long> {
    List<IncidentSignal> findByIncidentIdOrderByCreatedAtAsc(Long incidentId);
}
