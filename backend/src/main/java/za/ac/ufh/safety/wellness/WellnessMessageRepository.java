package za.ac.ufh.safety.wellness;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WellnessMessageRepository extends JpaRepository<WellnessMessage, Long> {
    List<WellnessMessage> findByStudentUserIdOrderBySentAtAsc(Long studentUserId);
}
