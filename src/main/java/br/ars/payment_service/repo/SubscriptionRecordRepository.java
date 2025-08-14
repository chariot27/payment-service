package br.ars.payment_service.repo;

import br.ars.payment_service.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface SubscriptionRecordRepository extends JpaRepository<SubscriptionRecord, UUID> {
  Optional<SubscriptionRecord> findByStripeSubscriptionId(String subId);
}