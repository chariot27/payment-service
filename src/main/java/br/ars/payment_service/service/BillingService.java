package br.ars.payment_service.service;

import br.ars.payment_service.dto.SubscribeRequest;
import br.ars.payment_service.dto.SubscribeResponse;
import br.ars.payment_service.dto.SubscriptionBackendStatus;
import br.ars.payment_service.dto.SubscriptionStatusResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.EphemeralKey;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.SubscriptionUpdateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
public class BillingService {

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  @Value("${app.stripe.secret-key}")
  private String stripeSecretKey;

  @Value("${app.stripe.publishable-key}")
  private String stripePublishableKey;

  @Value("${app.stripe.prices.basic}")
  private String defaultBasicPriceId;

  /** Versão da API usada pelo app móvel (PaymentSheet/ephemeral key) */
  @Value("${app.stripe.mobile-api-version:2020-08-27}")
  private String mobileApiVersionDefault;

  private final BillingCustomerService billingCustomerService;

  public BillingService(BillingCustomerService billingCustomerService) {
    this.billingCustomerService = billingCustomerService;
  }

  /**
   * Fluxo SEM TRIAL:
   * - Cria Subscription com DEFAULT_INCOMPLETE + expand latest_invoice.payment_intent
   * - Polling até o invoice ter PaymentIntent com client_secret (via getters OU raw JSON)
   * - Retorna o PI client_secret para a PaymentSheet
   * - Sem fallback para SetupIntent (evita cair em hasSI=true)
   */
  @Transactional
  public SubscribeResponse startSubscription(SubscribeRequest req) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final String userId = require(req.userId(), "userId");
    final String email = req.email();
    final String priceId = StringUtils.hasText(req.priceId()) ? req.priceId() : defaultBasicPriceId;
    final String stripeVersion = StringUtils.hasText(req.stripeVersion()) ? req.stripeVersion() : mobileApiVersionDefault;

    log.info("[BILL][FLOW] startSubscription (GPay) userId={}, email={}, priceId={}, stripeVersion={}",
        userId, email, priceId, stripeVersion);

    // 1) Customer vinculado ao usuário
    final String customerId = billingCustomerService.findOrCreateCustomer(userId, email);

    // 2) Cria assinatura “default_incomplete” para gerar fatura com PaymentIntent
    SubscriptionCreateParams params = SubscriptionCreateParams.builder()
        .setCustomer(customerId)
        .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
        .setCollectionMethod(SubscriptionCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
        .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
        .setPaymentSettings(SubscriptionCreateParams.PaymentSettings.builder()
            .setSaveDefaultPaymentMethod(SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION)
            .build())
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.payment_intent")
        .build();

    Subscription sub = Subscription.create(params);
    final String subscriptionId = sub.getId();

    // 3) Busca (com retry) o client_secret do PaymentIntent da invoice inicial
    String piClientSecret = fetchPIClientSecretBlocking(sub, Duration.ofSeconds(30));
    if (!StringUtils.hasText(piClientSecret)) {
      log.warn("[BILL][FLOW] Não foi possível obter payment_intent.client_secret da assinatura {} dentro do timeout", subscriptionId);
      throw new IllegalStateException("Falha ao preparar pagamento inicial. Tente novamente.");
    }

    // 4) Ephemeral Key na versão do mobile (compatível com libs antigas/novas)
    final String ephKeySecret = createEphemeralKeyCompat(customerId, stripeVersion);

    log.info("[BILL][FLOW][RES] subId={}, customerId={}, hasPI=true, hasSI=false", subscriptionId, customerId);

    // 5) Devolve dados para abrir a PaymentSheet em modo cobrança (PI)
    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        piClientSecret,      // PaymentSheet cobrará agora
        ephKeySecret,        // ephemeral key
        null,                // setupIntent CS (não usado no fluxo sem trial)
        null                 // hostedInvoiceUrl (não aplicável)
    );
  }

  /** Consulta status atual no Stripe e devolve DTO. */
  public SubscriptionStatusResponse getStatus(String subscriptionId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    Subscription sub = Subscription.retrieve(
        subscriptionId,
        SubscriptionRetrieveParams.builder().build(),
        null
    );
    final SubscriptionBackendStatus status = mapStatus(sub);

    String currentPeriodEndIso = null;
    try {
      Long epoch = tryGetLong(sub, "getCurrentPeriodEnd");
      if (epoch != null) {
        currentPeriodEndIso = Instant.ofEpochSecond(epoch)
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      }
    } catch (Throwable ignored) {}

    boolean cancelAtPeriodEnd = false;
    try {
      Boolean cpe = tryGetBoolean(sub, "getCancelAtPeriodEnd");
      cancelAtPeriodEnd = Boolean.TRUE.equals(cpe);
    } catch (Throwable ignored) {}

    return new SubscriptionStatusResponse(subscriptionId, status, currentPeriodEndIso, cancelAtPeriodEnd);
  }

  /** Compat para controladores que chamam getStatusAndUpsert. */
  public SubscriptionStatusResponse getStatusAndUpsert(String subscriptionId) throws StripeException {
    SubscriptionStatusResponse res = getStatus(subscriptionId);
    // TODO: se quiser, persista/upsert no seu banco aqui
    return res;
  }

  public void changePlan(String subscriptionId, String newPriceId, String prorationBehaviorRaw) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final Subscription sub = Subscription.retrieve(subscriptionId);
    final String itemId = (sub.getItems() != null && !sub.getItems().getData().isEmpty())
        ? sub.getItems().getData().get(0).getId() : null;

    final SubscriptionUpdateParams.Builder b = SubscriptionUpdateParams.builder();
    final SubscriptionUpdateParams.ProrationBehavior pb = parseProration(prorationBehaviorRaw);
    if (pb != null) b.setProrationBehavior(pb);

    if (StringUtils.hasText(itemId)) {
      b.addItem(SubscriptionUpdateParams.Item.builder().setId(itemId).setPrice(newPriceId).build());
    } else {
      b.addItem(SubscriptionUpdateParams.Item.builder().setPrice(newPriceId).build());
    }

    final Subscription updated = sub.update(b.build());
    log.info("[BILL][CHANGE_PLAN] subscriptionId={}, status={}", updated.getId(), updated.getStatus());
  }

  /** Chamada pelo WebhookController: persiste o status (ACTIVE/TRIALING/etc.) no seu banco. */
  public void applyWebhookUpdate(Subscription sub, Invoice inv) {
    try {
      final String subIdSafe = (sub != null) ? sub.getId() : null;
      final String invId = (inv != null) ? inv.getId() : null;
      final SubscriptionBackendStatus status = (sub != null) ? mapStatus(sub) : SubscriptionBackendStatus.INACTIVE;
      log.info("[BILL][WEBHOOK] subscriptionId={}, status={}, invoiceId={}", subIdSafe, status, invId);

      // TODO: upsert no seu banco (userId ↔ subscriptionId), datas (current_period_end), cancelAtPeriodEnd etc.
    } catch (Exception e) {
      log.error("[BILL][WEBHOOK][ERR] {}", e.getMessage(), e);
    }
  }

  // ====================== Internals ======================

  /**
   * Tenta obter o client_secret do PaymentIntent da invoice inicial (com retry até maxWait).
   * Estratégia:
   *   - Recarrega a subscription com expand latest_invoice.payment_intent
   *   - Tenta extrair o client_secret pelo objeto tipado OU do JSON cru do Invoice
   *   - Se não vier, busca o latest_invoice diretamente com expand=payment_intent
   *   - Retorna client_secret assim que aparecer
   */
  private String fetchPIClientSecretBlocking(Subscription initial, Duration maxWait) throws StripeException {
    final long deadline = System.nanoTime() + maxWait.toNanos();
    int attempt = 0;

    Subscription sub = initial;

    while (System.nanoTime() < deadline) {
      attempt++;

      // (A) via subscription expand
      try {
        sub = Subscription.retrieve(
            sub.getId(),
            SubscriptionRetrieveParams.builder()
                .addExpand("latest_invoice")
                .addExpand("latest_invoice.payment_intent")
                .build(),
            null
        );

        // 1) tentar pelo objeto tipado
        Invoice inv = sub.getLatestInvoiceObject(); // pode ser null em SDKs antigos
        if (inv != null) {
          // tenta PaymentIntent via compat
          PaymentIntent pi = getPaymentIntentCompat(inv);
          String cs = tryExtractClientSecret(pi);
          if (StringUtils.hasText(cs)) {
            log.info("[BILL][PI] CS via subscription expand/PI (tentativa {})", attempt);
            return cs;
          }
          // 2) fallback: tentar do JSON cru do próprio Invoice expandido
          cs = tryExtractClientSecretFromInvoiceRaw(inv);
          if (StringUtils.hasText(cs)) {
            log.info("[BILL][PI] CS via subscription expand/Invoice RAW (tentativa {})", attempt);
            return cs;
          }
        } else {
          // fallback extremo: tentar achar no JSON cru da Subscription
          String cs = tryExtractClientSecretFromSubscriptionRaw(sub);
          if (StringUtils.hasText(cs)) {
            log.info("[BILL][PI] CS via subscription RAW (tentativa {})", attempt);
            return cs;
          }
        }
      } catch (Throwable t) {
        log.debug("[BILL][PI] subscription expand falhou: {}", t.getMessage());
      }

      // (B) via retrieve direto da invoice com expand=payment_intent (compat)
      try {
        String invId = null;
        try { invId = sub.getLatestInvoice(); } catch (Throwable ignore) { /* fica null */ }

        if (StringUtils.hasText(invId)) {
          Invoice inv = Invoice.retrieve(
              invId,
              InvoiceRetrieveParams.builder().addExpand("payment_intent").build(),
              null
          );

          // 1) tenta PaymentIntent tipado/compat
          PaymentIntent pi = getPaymentIntentCompat(inv);
          String cs = tryExtractClientSecret(pi);
          if (StringUtils.hasText(cs)) {
            log.info("[BILL][PI] CS via invoice expand/PI (tentativa {})", attempt);
            return cs;
          }

          // 2) tenta JSON cru da Invoice
          cs = tryExtractClientSecretFromInvoiceRaw(inv);
          if (StringUtils.hasText(cs)) {
            log.info("[BILL][PI] CS via invoice RAW (tentativa {})", attempt);
            return cs;
          }
        }
      } catch (Throwable t) {
        log.debug("[BILL][PI] invoice expand falhou: {}", t.getMessage());
      }

      // (C) espera incremental
      long backoffMs = Math.min(2000L, 250L * attempt);
      try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    log.warn("[BILL][PI-RETRY] Timeout sem obter client_secret para sub {}", initial.getId());
    return null;
  }

  /** Obtém PaymentIntent do Invoice de forma compatível com diferentes versões do SDK. */
  private static PaymentIntent getPaymentIntentCompat(Invoice inv) throws StripeException {
    if (inv == null) return null;

    // 1) Tenta getPaymentIntentObject()
    try {
      Method mObj = Invoice.class.getMethod("getPaymentIntentObject");
      Object piObj = mObj.invoke(inv);
      if (piObj instanceof PaymentIntent) {
        return (PaymentIntent) piObj;
      }
    } catch (Throwable ignored) {}

    // 2) Tenta getPaymentIntent() -> String id
    try {
      Method mId = Invoice.class.getMethod("getPaymentIntent");
      Object idObj = mId.invoke(inv);
      if (idObj instanceof String piId && StringUtils.hasText(piId)) {
        return PaymentIntent.retrieve(piId);
      }
    } catch (Throwable ignored) {}

    return null;
  }

  /** Tenta extrair client_secret do PaymentIntent (getter ou JSON cru do lastResponse). */
  private static String tryExtractClientSecret(PaymentIntent pi) {
    if (pi == null) return null;

    // getter direto
    try {
      String cs = pi.getClientSecret();
      if (StringUtils.hasText(cs)) return cs;
    } catch (Throwable ignored) {}

    // reflection no getter (SDK antigas)
    try {
      Method m = pi.getClass().getMethod("getClientSecret");
      Object val = m.invoke(pi);
      if (val instanceof String s && StringUtils.hasText(s)) return s;
    } catch (Throwable ignored) {}

    // extrair do lastResponse.body (JSON cru)
    try {
      Object lastResp = invokeNoArg(pi, "getLastResponse");
      String body = extractBody(lastResp);
      if (body != null) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonElement cse = root.get("client_secret");
        if (cse != null && !cse.isJsonNull()) {
          String cs = cse.getAsString();
          if (StringUtils.hasText(cs)) return cs;
        }
      }
    } catch (Throwable ignored) {}

    return null;
  }

  /** Tenta extrair client_secret do JSON cru de uma Invoice expandida (payment_intent como objeto). */
  private static String tryExtractClientSecretFromInvoiceRaw(Invoice inv) {
    try {
      Object lastResp = invokeNoArg(inv, "getLastResponse");
      String body = extractBody(lastResp);
      if (body == null) return null;

      JsonObject root = JsonParser.parseString(body).getAsJsonObject();
      JsonElement pie = root.get("payment_intent");
      if (pie != null && pie.isJsonObject()) {
        JsonObject piObj = pie.getAsJsonObject();
        JsonElement cse = piObj.get("client_secret");
        if (cse != null && !cse.isJsonNull()) {
          String cs = cse.getAsString();
          if (StringUtils.hasText(cs)) return cs;
        }
      }
    } catch (Throwable ignored) {}
    return null;
  }

  /** Tenta extrair client_secret do JSON cru da Subscription expandida (latest_invoice.payment_intent). */
  private static String tryExtractClientSecretFromSubscriptionRaw(Subscription sub) {
    try {
      Object lastResp = invokeNoArg(sub, "getLastResponse");
      String body = extractBody(lastResp);
      if (body == null) return null;

      JsonObject root = JsonParser.parseString(body).getAsJsonObject();
      JsonElement invEl = root.get("latest_invoice");
      if (invEl != null && invEl.isJsonObject()) {
        JsonObject invObj = invEl.getAsJsonObject();
        JsonElement pie = invObj.get("payment_intent");
        if (pie != null && pie.isJsonObject()) {
          JsonObject piObj = pie.getAsJsonObject();
          JsonElement cse = piObj.get("client_secret");
          if (cse != null && !cse.isJsonNull()) {
            String cs = cse.getAsString();
            if (StringUtils.hasText(cs)) return cs;
          }
        }
      }
    } catch (Throwable ignored) {}
    return null;
  }

  /** Cria EphemeralKey configurando a Stripe-Version de forma compatível (override ou legacy). */
  private String createEphemeralKeyCompat(String customerId, String stripeVersion) throws StripeException {
    EphemeralKeyCreateParams params = EphemeralKeyCreateParams.builder()
        .setCustomer(customerId)
        .build();

    RequestOptions.RequestOptionsBuilder rb = RequestOptions.builder();

    // Tenta usar setStripeVersionOverride (SDKs mais novos); se não existir, tenta setStripeVersion; se nada, segue sem override.
    boolean versionSet = false;
    try {
      Method m = rb.getClass().getMethod("setStripeVersionOverride", String.class);
      m.invoke(rb, stripeVersion);
      versionSet = true;
    } catch (Throwable ignored) {}

    if (!versionSet) {
      try {
        Method m2 = rb.getClass().getMethod("setStripeVersion", String.class);
        m2.invoke(rb, stripeVersion);
        versionSet = true;
      } catch (Throwable ignored) {}
    }

    RequestOptions ro = rb.build();
    EphemeralKey ek = EphemeralKey.create(params, ro);
    if (ek == null || !StringUtils.hasText(ek.getSecret())) {
      throw new IllegalStateException("Falha ao criar EphemeralKey");
    }
    return ek.getSecret();
  }

  // ----- helpers de reflection/compat -----

  private static Object invokeNoArg(Object target, String method) {
    try {
      Method m = target.getClass().getMethod(method);
      return m.invoke(target);
    } catch (Throwable t) {
      return null;
    }
  }

  private static String extractBody(Object lastResponse) {
    if (lastResponse == null) return null;
    // tenta getBody()
    try {
      Method m = lastResponse.getClass().getMethod("getBody");
      Object val = m.invoke(lastResponse);
      if (val instanceof String s && !s.isEmpty()) return s;
    } catch (Throwable ignored) {}
    // tenta body()
    try {
      Method m = lastResponse.getClass().getMethod("body");
      Object val = m.invoke(lastResponse);
      if (val instanceof String s && !s.isEmpty()) return s;
    } catch (Throwable ignored) {}
    return null;
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

  /** Tenta invocar um getter booleano via reflection; retorna null se não existir. */
  private static Boolean tryGetBoolean(Object target, String method) {
    try {
      Method m = target.getClass().getMethod(method);
      Object val = m.invoke(target);
      if (val instanceof Boolean b) return b;
      return null;
    } catch (Throwable t) {
      return null;
    }
  }

  /** Tenta invocar um getter Long via reflection; retorna null se não existir. */
  private static Long tryGetLong(Object target, String method) {
    try {
      Method m = target.getClass().getMethod(method);
      Object val = m.invoke(target);
      if (val instanceof Long l) return l;
      if (val instanceof Number n) return n.longValue();
      return null;
    } catch (Throwable t) {
      return null;
    }
  }
}
