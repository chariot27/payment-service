package br.ars.payment_service.dto;

import java.util.Map;
import java.util.UUID;

public record SubscribeRequest(
  UUID userId,
  String email,
  String priceId,              // opcional: se não vier, usa default (basic)
  Map<String,String> metadata  // opcional
) {}
