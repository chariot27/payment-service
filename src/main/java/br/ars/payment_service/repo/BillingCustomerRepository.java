package br.ars.payment_service.repo;

import br.ars.payment_service.domain.BillingCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, UUID> {
  Optional<BillingCustomer> findByUserId(UUID userId);
  Optional<BillingCustomer> findByStripeCustomerId(String stripeCustomerId);
}