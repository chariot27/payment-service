package br.ars.payment_service.dto;

public record SubscribeResponse(
  String publishableKey,
  String customerId,
  String subscriptionId,
  String paymentIntentClientSecret,
  String ephemeralKeySecret
) {}