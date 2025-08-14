package br.ars.payment_service.dto;

/** Request recebido do app/gateway para iniciar a assinatura. */
public record SubscribeRequest(
    String userId,
    String email,
    String priceId,
    String stripeVersion // versão da API do Stripe usada no mobile (ex.: "2020-08-27")
) {}
