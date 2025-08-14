package br.ars.payment_service.service;

import br.ars.payment_service.dto.SubscribeRequest;
import br.ars.payment_service.dto.SubscribeResponse;
import br.ars.payment_service.dto.SubscriptionBackendStatus;
import br.ars.payment_service.dto.SubscriptionStatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.EphemeralKey;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Service
public class BillingService {

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  @Value("${app.stripe.secret-key}")
  private String stripeSecretKey;

  @Value("${app.stripe.publishable-key}")
  private String stripePublishableKey;

  @Value("${app.stripe.prices.basic}")
  private String defaultBasicPriceId;

  /** Versão da API usada pelo SDK mobile (RN/Android/iOS) */
  @Value("${app.stripe.mobile-api-version:2020-08-27}")
  private String mobileApiVersionDefault;

  private final BillingCustomerService billingCustomerService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  public BillingService(BillingCustomerService billingCustomerService) {
    this.billingCustomerService = billingCustomerService;
  }

  @Transactional
  public SubscribeResponse startSubscription(SubscribeRequest req) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final String userId = require(req.userId(), "userId");
    final String email = req.email();
    final String priceId = StringUtils.hasText(req.priceId()) ? req.priceId() : defaultBasicPriceId;
    final String stripeVersion = StringUtils.hasText(req.stripeVersion())
        ? req.stripeVersion() : mobileApiVersionDefault;

    log.info("[BILL][FLOW] startSubscription userId={}, email={}, priceId={}, stripeVersion={}",
        userId, email, priceId, stripeVersion);

    // 1) Customer (cria/recupera e persiste no seu BD)
    String customerId = billingCustomerService.findOrCreateCustomer(userId, email);

    // 2) Cria assinatura INCOMPLETE (PaymentSheet vai confirmar)
    SubscriptionCreateParams subParams = SubscriptionCreateParams.builder()
        .setCustomer(customerId)
        .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
        .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
        .setPaymentSettings(
            SubscriptionCreateParams.PaymentSettings.builder()
                .setSaveDefaultPaymentMethod(
                    SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION)
                .build()
        )
        .addExpand("latest_invoice.payment_intent")
        .build();

    Subscription subscription = Subscription.create(subParams);
    String subscriptionId = subscription.getId();

    // 3) Extrai o client_secret do PaymentIntent via JSON bruto da resposta
    String paymentIntentClientSecret = extractClientSecretFromSubscription(subscription);

    // 4) Ephemeral Key: especifique a versão da API nos PARAMS
    Map<String, Object> ekParams = new HashMap<>();
    ekParams.put("customer", customerId);
    ekParams.put("stripe_version", stripeVersion); // compat
    ekParams.put("api_version",    stripeVersion); // compat

    EphemeralKey ek = EphemeralKey.create(ekParams);

    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        paymentIntentClientSecret,
        ek.getSecret()
    );
  }

  public SubscriptionStatusResponse getStatus(String subscriptionId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;
    Subscription sub = Subscription.retrieve(subscriptionId);

    SubscriptionBackendStatus status = mapStatus(sub);

    // Seu SDK não expõe current_period_end -> mantemos null (campo é opcional no DTO).
    String currentPeriodEndIso = null;

    boolean cancelAtPeriodEnd = false;
    try {
      Boolean v = sub.getCancelAtPeriodEnd();
      cancelAtPeriodEnd = Boolean.TRUE.equals(v);
    } catch (Throwable ignored) {}

    return new SubscriptionStatusResponse(subscriptionId, status, currentPeriodEndIso, cancelAtPeriodEnd);
  }

  public void changePlan(String subscriptionId, String newPriceId, String prorationBehaviorRaw) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    Subscription sub = Subscription.retrieve(subscriptionId);
    String itemId = (sub.getItems() != null && !sub.getItems().getData().isEmpty())
        ? sub.getItems().getData().get(0).getId()
        : null;

    SubscriptionUpdateParams.Builder b = SubscriptionUpdateParams.builder();

    // parse manual do proration (sua versão não tem fromValue(String))
    SubscriptionUpdateParams.ProrationBehavior pb = parseProration(prorationBehaviorRaw);
    if (pb != null) b.setProrationBehavior(pb);

    if (itemId != null) {
      b.addItem(SubscriptionUpdateParams.Item.builder()
          .setId(itemId)
          .setPrice(newPriceId)
          .build());
    } else {
      b.addItem(SubscriptionUpdateParams.Item.builder()
          .setPrice(newPriceId)
          .build());
    }

    // Em versões beta recentes o update é de instância
    Subscription updated = sub.update(b.build());
    log.info("[BILL][CHANGE_PLAN] subscriptionId={}, status={}", updated.getId(), updated.getStatus());
  }

  public void applyWebhookUpdate(Subscription sub, Invoice inv /* pode ser null */) {
    try {
      SubscriptionBackendStatus status = mapStatus(sub);
      String invId = (inv != null) ? inv.getId() : null;
      log.info("[BILL][WEBHOOK] subId={}, status={}, invoiceId={}", sub.getId(), status, invId);
      // TODO: persistir no seu repositório local
    } catch (Exception e) {
      log.error("[BILL][WEBHOOK][ERR] {}", e.getMessage(), e);
    }
  }

  // ---- helpers ----

  private String extractClientSecretFromSubscription(Subscription subscription) {
      try {
          // Extrai do JSON da resposta expandida
          String rawResponse = subscription.getLastResponse().body();
          JsonNode rootNode = objectMapper.readTree(rawResponse);
          
          JsonNode paymentIntentNode = rootNode.path("latest_invoice")
                                            .path("payment_intent");
          
          if (!paymentIntentNode.isMissingNode()) {
              return paymentIntentNode.path("client_secret").asText();
          }
          return null;
      } catch (Exception e) {
          log.warn("[BILL] Falha ao extrair client_secret do JSON expandido: {}", e.getMessage());
          return null;
      }
  }

  private static String require(String v, String field) {
    if (!StringUtils.hasText(v)) throw new IllegalArgumentException(field + " é obrigatório");
    return v;
  }

  private static SubscriptionBackendStatus mapStatus(Subscription sub) {
    if (sub == null || sub.getStatus() == null) return SubscriptionBackendStatus.INACTIVE;
    return switch (sub.getStatus()) {
      case "incomplete" -> SubscriptionBackendStatus.INCOMPLETE;
      case "incomplete_expired" -> SubscriptionBackendStatus.INCOMPLETE_EXPIRED;
      case "trialing" -> SubscriptionBackendStatus.TRIALING;
      case "active" -> SubscriptionBackendStatus.ACTIVE;
      case "past_due" -> SubscriptionBackendStatus.PAST_DUE;
      case "canceled" -> SubscriptionBackendStatus.CANCELED;
      case "unpaid" -> SubscriptionBackendStatus.UNPAID;
      default -> SubscriptionBackendStatus.INACTIVE;
    };
  }

  private static SubscriptionUpdateParams.ProrationBehavior parseProration(String s) {
    if (!StringUtils.hasText(s)) return null;
    return switch (s) {
      case "create_prorations" -> SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS;
      case "none" -> SubscriptionUpdateParams.ProrationBehavior.NONE;
      case "always_invoice" -> SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE;
      default -> null;
    };
  }
}