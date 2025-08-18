package br.ars.payment_service.dto;

public record ConfirmPaymentRequest(
    String subscriptionId,
    String paymentMethodId
) {}
