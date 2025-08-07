package br.ars.payment_service.repository;

import br.ars.payment_service.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Payment findByStripePaymentId(String id);
}
