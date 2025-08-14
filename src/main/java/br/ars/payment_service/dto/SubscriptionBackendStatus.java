package br.ars.payment_service.dto;

public enum SubscriptionBackendStatus {
  INCOMPLETE,
  INCOMPLETE_EXPIRED,
  TRIALING,
  ACTIVE,
  PAST_DUE,
  CANCELED,
  UNPAID,
  INACTIVE
}
