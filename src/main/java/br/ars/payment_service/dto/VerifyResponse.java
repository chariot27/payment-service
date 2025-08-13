package br.ars.payment_service.dto;

public record VerifyResponse(
        String txid,
        String paymentStatus,
        String subscriptionStatus,
        boolean changed
) {}
