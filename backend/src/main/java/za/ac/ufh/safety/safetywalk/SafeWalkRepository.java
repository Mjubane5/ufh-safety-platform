package za.ac.ufh.safety.safetywalk;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafeWalkRepository extends JpaRepository<SafeWalk, Long> {
    List<SafeWalk> findByStatus(SafeWalkStatus status);
}
