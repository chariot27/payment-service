package br.ars.payment_service.dto;

public record ConfirmInitialPaymentRequest(
    String subscriptionId,
    String paymentMethodId
) {}
