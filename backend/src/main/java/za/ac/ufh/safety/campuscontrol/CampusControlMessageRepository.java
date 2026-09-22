package za.ac.ufh.safety.campuscontrol;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampusControlMessageRepository extends JpaRepository<CampusControlMessage, Long> {
    List<CampusControlMessage> findByStudentUserIdOrderBySentAtAsc(Long studentUserId);
}
