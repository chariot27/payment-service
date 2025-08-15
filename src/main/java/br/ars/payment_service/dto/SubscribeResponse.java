package br.ars.payment_service.dto;

public class SubscribeResponse {
  private final String publishableKey;
  private final String customerId;
  private final String subscriptionId;
  private final String paymentIntentClientSecret; // continua possível (para outros fluxos)
  private final String ephemeralKeySecret;
  private final String setupIntentClientSecret;   // NOVO

  public SubscribeResponse(String publishableKey, String customerId, String subscriptionId,
                           String paymentIntentClientSecret, String ephemeralKeySecret,
                           String setupIntentClientSecret) {
    this.publishableKey = publishableKey;
    this.customerId = customerId;
    this.subscriptionId = subscriptionId;
    this.paymentIntentClientSecret = paymentIntentClientSecret;
    this.ephemeralKeySecret = ephemeralKeySecret;
    this.setupIntentClientSecret = setupIntentClientSecret;
  }

  public String getPublishableKey() {
    return publishableKey;
  }

  public String getCustomerId() {
    return customerId;
  }

  public String getSubscriptionId() {
    return subscriptionId;
  }

  public String getPaymentIntentClientSecret() {
    return paymentIntentClientSecret;
  }

  public String getEphemeralKeySecret() {
    return ephemeralKeySecret;
  }

  public String getSetupIntentClientSecret() {
    return setupIntentClientSecret;
  }

  // getters...
  
}
