package br.ars.payment_service.repo;

import br.ars.payment_service.domain.Payment;
import br.ars.payment_service.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByTxid(String txid);
    List<Payment> findByStatusAndExpiresAtBefore(PaymentStatus status, OffsetDateTime before);
}
