package za.ac.ufh.safety.gbv;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GbvReportRepository extends JpaRepository<GbvReport, Long> {
    Optional<GbvReport> findByReferenceCode(String referenceCode);
    boolean existsByReferenceCode(String referenceCode);
    Page<GbvReport> findByStatus(GbvReportStatus status, Pageable pageable);
}
