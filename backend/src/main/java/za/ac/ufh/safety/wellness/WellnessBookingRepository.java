package za.ac.ufh.safety.wellness;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WellnessBookingRepository extends JpaRepository<WellnessBooking, Long> {
}
