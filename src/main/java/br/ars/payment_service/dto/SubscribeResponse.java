package br.ars.payment_service.dto;

public class SubscribeResponse {
  private String publishableKey;
  private String customerId;
  private String subscriptionId;
  private String paymentIntentClientSecret; // pode ser null
  private String ephemeralKeySecret;
  private String setupIntentClientSecret;   // pode ser null (trial/auto)
  private String hostedInvoiceUrl;          // pode ser null (boleto/send_invoice)

  public SubscribeResponse() {}

  public SubscribeResponse(
      String publishableKey,
      String customerId,
      String subscriptionId,
      String paymentIntentClientSecret,
      String ephemeralKeySecret,
      String setupIntentClientSecret,
      String hostedInvoiceUrl
  ) {
    this.publishableKey = publishableKey;
    this.customerId = customerId;
    this.subscriptionId = subscriptionId;
    this.paymentIntentClientSecret = paymentIntentClientSecret;
    this.ephemeralKeySecret = ephemeralKeySecret;
    this.setupIntentClientSecret = setupIntentClientSecret;
    this.hostedInvoiceUrl = hostedInvoiceUrl;
  }

  public String getPublishableKey() { return publishableKey; }
  public String getCustomerId() { return customerId; }
  public String getSubscriptionId() { return subscriptionId; }
  public String getPaymentIntentClientSecret() { return paymentIntentClientSecret; }
  public String getEphemeralKeySecret() { return ephemeralKeySecret; }
  public String getSetupIntentClientSecret() { return setupIntentClientSecret; }
  public String getHostedInvoiceUrl() { return hostedInvoiceUrl; }

  public void setPublishableKey(String publishableKey) { this.publishableKey = publishableKey; }
  public void setCustomerId(String customerId) { this.customerId = customerId; }
  public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
  public void setPaymentIntentClientSecret(String v) { this.paymentIntentClientSecret = v; }
  public void setEphemeralKeySecret(String v) { this.ephemeralKeySecret = v; }
  public void setSetupIntentClientSecret(String v) { this.setupIntentClientSecret = v; }
  public void setHostedInvoiceUrl(String v) { this.hostedInvoiceUrl = v; }
}
