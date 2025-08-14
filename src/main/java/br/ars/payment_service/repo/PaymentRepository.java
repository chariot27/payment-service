// src/main/java/br/ars/payment_service/repo/PaymentRepository.java
package br.ars.payment_service.repo;

import br.ars.payment_service.domain.Payment;
import br.ars.payment_service.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByTxid(String txid);

    Optional<Payment> findByEndToEndId(String endToEndId);

    /** Pega o PENDING mais "recente" (ordenando pelo expiresAt) desse valor e ainda válido */
    Optional<Payment> findFirstByStatusAndAmountAndExpiresAtAfterOrderByExpiresAtDesc(
            PaymentStatus status, BigDecimal amount, OffsetDateTime now
    );
}
