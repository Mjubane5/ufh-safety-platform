package za.ac.ufh.safety.health;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthMessageRepository extends JpaRepository<HealthMessage, Long> {
    List<HealthMessage> findByStudentUserIdOrderBySentAtAsc(Long studentUserId);
}
