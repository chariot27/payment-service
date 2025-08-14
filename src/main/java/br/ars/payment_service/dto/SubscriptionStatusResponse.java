package br.ars.payment_service.dto;

import br.ars.payment_service.domain.SubscriptionsStatus;
import java.time.OffsetDateTime;

public record SubscriptionStatusResponse(
  String subscriptionId,
  SubscriptionsStatus status,
  OffsetDateTime currentPeriodEnd,
  boolean cancelAtPeriodEnd
) {}
