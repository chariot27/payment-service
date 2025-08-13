package br.ars.payment_service.dto;

public record StatusResponse(String txid, String paymentStatus, String subscriptionStatus) {}
