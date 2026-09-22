package za.ac.ufh.safety.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByStudentNumber(String studentNumber);
    Optional<User> findByStudentNumber(String studentNumber);
}
