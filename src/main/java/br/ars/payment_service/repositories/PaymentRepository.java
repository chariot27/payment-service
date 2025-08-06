package br.ars.payment_service.repositories;

import br.ars.payment_service.models.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    // Aqui você pode adicionar filtros específicos no futuro, como:
    // List<Payment> findByAssinaturaId(String assinaturaId);
}
