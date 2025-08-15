package br.ars.payment_service.dto;

public record SubscribeRequest(
  String userId,
  String email,
  String priceId,
  String stripeVersion,
  String pmMode // opcional; default "auto"
) {}

