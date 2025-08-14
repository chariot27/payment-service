package br.ars.payment_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "app.stripe")
public class StripeProperties {

  @NotBlank
  private String publishableKey;

  @NotBlank
  private String secretKey;

  /** Assine seus webhooks com esse segredo (opcional em dev) */
  private String webhookSecret;

  /** Versão da API usada pelo cliente móvel (opcional) */
  private String apiVersion;

  /** app.stripe.prices.* */
  private Prices prices = new Prices();

  // ---- nested ----
  public static class Prices {
    /** app.stripe.prices.basic */
    @NotBlank
    private String basic;

    public String getBasic() { return basic; }
    public void setBasic(String basic) { this.basic = basic; }
  }

  // ---- getters/setters ----
  public String getPublishableKey() { return publishableKey; }
  public void setPublishableKey(String publishableKey) { this.publishableKey = publishableKey; }

  public String getSecretKey() { return secretKey; }
  public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

  public String getWebhookSecret() { return webhookSecret; }
  public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

  public String getApiVersion() { return apiVersion; }
  public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

  /** Permite mapear também a chave `app.stripe.mobile-api-version` */
  public void setMobileApiVersion(String apiVersion) { this.apiVersion = apiVersion; }

  public Prices getPrices() { return prices; }
  public void setPrices(Prices prices) { this.prices = prices; }
}
