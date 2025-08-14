package br.ars.payment_service.dto;

public record ChangePlanRequest(
    String subscriptionId,
    String newPriceId,
    String prorationBehavior // "create_prorations" | "none" | "always_invoice"
) {}
