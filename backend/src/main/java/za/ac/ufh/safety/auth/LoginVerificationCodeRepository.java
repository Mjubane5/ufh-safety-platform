package za.ac.ufh.safety.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoginVerificationCodeRepository extends JpaRepository<LoginVerificationCode, Long> {
    Optional<LoginVerificationCode> findByPendingLoginId(String pendingLoginId);

    Optional<LoginVerificationCode> findTopByUserIdOrderByLastSentAtDesc(Long userId);
}
