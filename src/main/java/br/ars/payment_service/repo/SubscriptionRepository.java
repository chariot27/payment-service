package br.ars.payment_service.repo;

import br.ars.payment_service.domain.Subscription;
import br.ars.payment_service.domain.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByUserId(UUID userId);
    List<Subscription> findByStatusAndCurrentPeriodEndBefore(SubscriptionStatus status, OffsetDateTime before);
}
