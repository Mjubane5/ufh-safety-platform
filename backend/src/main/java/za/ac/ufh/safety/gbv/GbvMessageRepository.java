package za.ac.ufh.safety.gbv;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GbvMessageRepository extends JpaRepository<GbvMessage, Long> {
    List<GbvMessage> findByReferenceCodeOrderBySentAtAsc(String referenceCode);

    // Only reference codes that have at least one message are relevant to
    // the chat queue - findByReferenceCodeIn lets the service fetch every
    // message for every non-anonymous report in one query rather than one
    // query per report.
    List<GbvMessage> findByReferenceCodeIn(List<String> referenceCodes);
}
