package br.ars.payment_service.dto;

public record SubscriptionStatusResponse(
    String subscriptionId,
    SubscriptionBackendStatus status,
    String currentPeriodEnd,     // ISO-8601 (UTC) ou null
    boolean cancelAtPeriodEnd
) {}
