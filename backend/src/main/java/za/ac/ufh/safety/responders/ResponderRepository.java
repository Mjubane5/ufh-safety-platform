package za.ac.ufh.safety.responders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResponderRepository extends JpaRepository<Responder, Long> {

    /**
     * Everyone currently free. Ordering is left to the caller, because the
     * dispatcher list wants them by name while auto-assignment wants them by
     * distance from one particular incident.
     */
    List<Responder> findByStatus(ResponderStatus status);
}
