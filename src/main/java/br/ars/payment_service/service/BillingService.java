package br.ars.payment_service.service;

import br.ars.payment_service.dto.SubscribeRequest;
import br.ars.payment_service.dto.SubscribeResponse;
import br.ars.payment_service.dto.SubscriptionBackendStatus;
import br.ars.payment_service.dto.SubscriptionStatusResponse;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.EphemeralKey;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.SetupIntent;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Service
public class BillingService {

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  @Value("${app.stripe.secret-key}")
  private String stripeSecretKey;

  @Value("${app.stripe.publishable-key}")
  private String stripePublishableKey;

  @Value("${app.stripe.prices.basic}")
  private String defaultBasicPriceId;

  /** Versão da API usada pelo app móvel ao criar EphemeralKey */
  @Value("${app.stripe.mobile-api-version:2020-08-27}")
  private String mobileApiVersionDefault;

  /** Opcional: força uma Stripe-Version moderna no fallback HTTP (mantém compat com o SDK antigo). */
  @Value("${app.stripe.http-api-version:2023-10-16}")
  private String httpApiVersion;

  private final BillingCustomerService billingCustomerService;

  private final HttpClient http = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_2)
      .build();

  private final ObjectMapper json = new ObjectMapper();

  public BillingService(BillingCustomerService billingCustomerService) {
    this.billingCustomerService = billingCustomerService;
  }

  /** Cria assinatura DEFAULT_INCOMPLETE; devolve PI client_secret OU SI client_secret. */
  @Transactional
  public SubscribeResponse startSubscription(SubscribeRequest req) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final String userId = require(req.userId(), "userId");
    final String email = req.email();
    final String priceId = StringUtils.hasText(req.priceId()) ? req.priceId() : defaultBasicPriceId;
    final String stripeVersion = StringUtils.hasText(req.stripeVersion()) ? req.stripeVersion() : mobileApiVersionDefault;

    log.info("[BILL][FLOW] startSubscription (cards/googlepay) userId={}, email={}, priceId={}, stripeVersion={}",
        userId, email, priceId, stripeVersion);

    // 1) Customer
    final String customerId = billingCustomerService.findOrCreateCustomer(userId, email);

    // 2) Cria assinatura: DEFAULT_INCOMPLETE + salvar PM na assinatura
    final SubscriptionCreateParams params = SubscriptionCreateParams.builder()
        .setCustomer(customerId)
        .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
        .setCollectionMethod(SubscriptionCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
        .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
        .setPaymentSettings(
            SubscriptionCreateParams.PaymentSettings.builder()
                .setSaveDefaultPaymentMethod(
                    SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                )
                .build()
        )
        // expansões iniciais (alguns jars ignoram nested aqui; por isso haverá um retrieve depois)
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.payment_intent")
        .addExpand("pending_setup_intent")
        .build();

    final Subscription subCreated = Subscription.create(params);
    final String subscriptionId = subCreated.getId();

    // 3) Segundo retrieve COM as expansões corretas (garante objetos populados quando o SDK permite)
    final SubscriptionRetrieveParams srp = SubscriptionRetrieveParams.builder()
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.payment_intent")
        .addExpand("pending_setup_intent")
        .build();
    final Subscription sub = Subscription.retrieve(subscriptionId, srp, (RequestOptions) null);

    // 4) Obter a Invoice
    Invoice inv = safeGetLatestInvoice(sub);
    String invIdForLog = (inv != null ? inv.getId() : null);
    if (inv == null) {
      final String invId = sub.getLatestInvoice();
      if (StringUtils.hasText(invId)) {
        final InvoiceRetrieveParams irp = InvoiceRetrieveParams.builder()
            .addExpand("payment_intent")
            .build();
        inv = Invoice.retrieve(invId, irp, (RequestOptions) null);
        invIdForLog = inv != null ? inv.getId() : invId;
      }
    }

    // 5) Tenta extrair PI client_secret (SDK / reflexão)
    String paymentIntentClientSecret = tryExtractClientSecretFromInvoice(inv);

    // 6) Se ainda não veio via SDK, tenta via HTTP direto
    if (!StringUtils.hasText(paymentIntentClientSecret) && StringUtils.hasText(invIdForLog)) {
      paymentIntentClientSecret = fetchPaymentIntentSecretHttp(invIdForLog);
    }

    // 7) Se não houver PI, tenta SetupIntent (SDK / reflexão / HTTP)
    String setupIntentClientSecret = null;
    String siIdForLog = null;

    if (!StringUtils.hasText(paymentIntentClientSecret)) {
      setupIntentClientSecret = tryExtractSetupIntentClientSecret(sub);
      siIdForLog = tryGetPendingSetupIntentId(sub);

      if (!StringUtils.hasText(setupIntentClientSecret)) {
        setupIntentClientSecret = fetchSetupIntentSecretHttp(subscriptionId);
        // siId só para log quando veio por HTTP
        if (!StringUtils.hasText(siIdForLog) && StringUtils.hasText(setupIntentClientSecret)) {
          siIdForLog = "(via-http)";
        }
      }
    }

    // 8) Ephemeral Key para o app
    final EphemeralKey ek = EphemeralKey.create(
        EphemeralKeyCreateParams.builder()
            .setCustomer(customerId)
            .setStripeVersion(stripeVersion)
            .build()
    );

    log.info("[BILL][FLOW][RES] subId={}, customerId={}, invId={}, hasPI={}, siId={}, hasSI={}",
        subscriptionId, customerId, invIdForLog, paymentIntentClientSecret != null, siIdForLog, setupIntentClientSecret != null);

    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        paymentIntentClientSecret,   // PaymentSheet paga a fatura inicial (quando existir)
        ek.getSecret(),
        setupIntentClientSecret,     // PaymentSheet salva PM e ativa trial/auto (quando não existir PI)
        null
    );
  }

  /** Confirma manualmente o PaymentIntent inicial (opcional para PI; não usado para SI). */
  public void confirmInitialPayment(String subscriptionId, String paymentMethodId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final SubscriptionRetrieveParams srp = SubscriptionRetrieveParams.builder()
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.payment_intent")
        .build();
    final Subscription sub = Subscription.retrieve(subscriptionId, srp, (RequestOptions) null);

    final Invoice inv = safeGetLatestInvoice(sub);
    final String piId = tryExtractPaymentIntentIdFromInvoice(inv);
    if (!StringUtils.hasText(piId)) {
      throw new IllegalStateException("PaymentIntent não encontrado na fatura inicial.");
    }

    final PaymentIntent pi = PaymentIntent.retrieve(piId);
    final PaymentIntentConfirmParams.Builder b = PaymentIntentConfirmParams.builder();
    if (StringUtils.hasText(paymentMethodId)) {
      b.setPaymentMethod(paymentMethodId);
    }
    pi.confirm(b.build());
    log.info("[BILL][CONFIRM_PI] subscriptionId={}, piId={}", subscriptionId, pi.getId());
  }

  /** Mantém compatibilidade com o controller atual. */
  public SubscriptionStatusResponse getStatusAndUpsert(String subscriptionId) throws StripeException {
    return getStatus(subscriptionId);
  }

  public SubscriptionStatusResponse getStatus(String subscriptionId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;
    final Subscription sub = Subscription.retrieve(subscriptionId);
    final SubscriptionBackendStatus status = mapStatus(sub);

    String currentPeriodEndIso = null;
    boolean cancelAtPeriodEnd = false;
    try { cancelAtPeriodEnd = Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()); } catch (Throwable ignored) {}

    return new SubscriptionStatusResponse(subscriptionId, status, currentPeriodEndIso, cancelAtPeriodEnd);
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

  /** Usado pelo StripeWebhookController */
  public void applyWebhookUpdate(Subscription sub, Invoice inv) {
    try {
      final String subIdSafe = (sub != null) ? sub.getId() : null;
      final String invId = (inv != null) ? inv.getId() : null;
      final SubscriptionBackendStatus status = (sub != null) ? mapStatus(sub) : SubscriptionBackendStatus.INACTIVE;
      log.info("[BILL][WEBHOOK] subscriptionId={}, status={}, invoiceId={}", subIdSafe, status, invId);
      // TODO: persistir status/datas em DB e publicar eventos internos
    } catch (Exception e) {
      log.error("[BILL][WEBHOOK][ERR] {}", e.getMessage(), e);
    }
  }

  // ---------------- helpers (SDK/reflection) ----------------

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

  private static Invoice safeGetLatestInvoice(Subscription sub) {
    if (sub == null) return null;
    try {
      Invoice inv = sub.getLatestInvoiceObject();
      if (inv != null) return inv;
    } catch (Throwable ignored) {}
    try {
      final String invId = sub.getLatestInvoice();
      if (StringUtils.hasText(invId)) {
        return Invoice.retrieve(invId);
      }
    } catch (Throwable ignored) {}
    return null;
  }

  private static String tryExtractClientSecretFromInvoice(Invoice inv) throws StripeException {
    if (inv == null) return null;

    // 1) confirmation_secret (quando disponível no SDK)
    try {
      Method mCs = inv.getClass().getMethod("getConfirmationSecret");
      Object cs = mCs.invoke(inv);
      if (cs != null) {
        Method mSecret = cs.getClass().getMethod("getClientSecret");
        Object secret = mSecret.invoke(cs);
        if (secret instanceof String s && StringUtils.hasText(s)) return s;
      }
    } catch (Throwable ignored) {}

    // 2) payment_intent expandido
    try {
      Method mObj = inv.getClass().getMethod("getPaymentIntentObject");
      Object piObj = mObj.invoke(inv);
      if (piObj instanceof PaymentIntent pi) {
        String s = pi.getClientSecret();
        if (StringUtils.hasText(s)) return s;
      }
    } catch (Throwable ignored) {}

    // 3) payment_intent como id + retrieve
    try {
      Method mId = inv.getClass().getMethod("getPaymentIntent");
      Object id = mId.invoke(inv);
      if (id instanceof String s && StringUtils.hasText(s)) {
        PaymentIntent pi = PaymentIntent.retrieve(s);
        if (pi != null && StringUtils.hasText(pi.getClientSecret())) return pi.getClientSecret();
      }
    } catch (Throwable ignored) {}

    return null;
  }

  private static String tryExtractPaymentIntentIdFromInvoice(Invoice inv) {
    if (inv == null) return null;
    try {
      Method mId = inv.getClass().getMethod("getPaymentIntent");
      Object id = mId.invoke(inv);
      if (id instanceof String s && StringUtils.hasText(s)) return s;
    } catch (Throwable ignored) {}
    try {
      Method mObj = inv.getClass().getMethod("getPaymentIntentObject");
      Object piObj = mObj.invoke(inv);
      if (piObj instanceof PaymentIntent pi) {
        return pi.getId();
      }
    } catch (Throwable ignored) {}
    return null;
  }

  private static String tryExtractSetupIntentClientSecret(Subscription sub) throws StripeException {
    if (sub == null) return null;

    // 1) objeto expandido
    try {
      Method mObj = sub.getClass().getMethod("getPendingSetupIntentObject");
      Object siObj = mObj.invoke(sub);
      if (siObj instanceof SetupIntent si) {
        String s = si.getClientSecret();
        if (StringUtils.hasText(s)) return s;
      }
    } catch (Throwable ignored) {}

    // 2) id simples + retrieve
    try {
      Method mId = sub.getClass().getMethod("getPendingSetupIntent");
      Object id = mId.invoke(sub);
      if (id instanceof String s && StringUtils.hasText(s)) {
        SetupIntent si = SetupIntent.retrieve(s);
        if (si != null && StringUtils.hasText(si.getClientSecret())) return si.getClientSecret();
      }
    } catch (Throwable ignored) {}

    return null;
  }

  private static String tryGetPendingSetupIntentId(Subscription sub) {
    if (sub == null) return null;
    try {
      Method mId = sub.getClass().getMethod("getPendingSetupIntent");
      Object id = mId.invoke(sub);
      if (id instanceof String s && StringUtils.hasText(s)) return s;
    } catch (Throwable ignored) {}
    try {
      Method mObj = sub.getClass().getMethod("getPendingSetupIntentObject");
      Object siObj = mObj.invoke(sub);
      if (siObj instanceof SetupIntent si) return si.getId();
    } catch (Throwable ignored) {}
    return null;
  }

  // ---------------- fallbacks HTTP diretos na API Stripe ----------------

  /** Busca o client_secret do PaymentIntent da fatura via HTTP, independente do SDK. */
  private String fetchPaymentIntentSecretHttp(String invoiceId) {
    try {
      final String url = "https://api.stripe.com/v1/invoices/" +
          URLEncoder.encode(invoiceId, StandardCharsets.UTF_8) +
          "?expand[]=payment_intent";

      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", "Bearer " + stripeSecretKey)
          .header("User-Agent", "ars-payment-service/1.0")
          .header("Content-Type", "application/x-www-form-urlencoded")
          .GET();

      if (StringUtils.hasText(httpApiVersion)) {
        b.header("Stripe-Version", httpApiVersion);
      }

      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 200 && res.statusCode() < 300) {
        JsonNode root = json.readTree(res.body());
        JsonNode piNode = root.get("payment_intent");
        if (piNode != null) {
          if (piNode.isObject()) {
            String secret = textOrNull(piNode.get("client_secret"));
            if (StringUtils.hasText(secret)) return secret;
            // como fallback, se vier objeto sem secret, pega id e busca /payment_intents/{id}
            String piId = textOrNull(piNode.get("id"));
            if (StringUtils.hasText(piId)) {
              return fetchPaymentIntentSecretByIdHttp(piId);
            }
          } else if (piNode.isTextual()) {
            return fetchPaymentIntentSecretByIdHttp(piNode.asText());
          }
        }
      } else {
        log.warn("[BILL][HTTP][INV] status={} body={}", res.statusCode(), res.body());
      }
    } catch (Exception e) {
      log.warn("[BILL][HTTP][INV][ERR] {}", e.toString());
    }
    return null;
  }

  private String fetchPaymentIntentSecretByIdHttp(String piId) {
    if (!StringUtils.hasText(piId)) return null;
    try {
      final String url = "https://api.stripe.com/v1/payment_intents/" +
          URLEncoder.encode(piId, StandardCharsets.UTF_8);

      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", "Bearer " + stripeSecretKey)
          .header("User-Agent", "ars-payment-service/1.0")
          .header("Content-Type", "application/x-www-form-urlencoded")
          .GET();

      if (StringUtils.hasText(httpApiVersion)) {
        b.header("Stripe-Version", httpApiVersion);
      }

      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 200 && res.statusCode() < 300) {
        JsonNode root = json.readTree(res.body());
        String secret = textOrNull(root.get("client_secret"));
        if (StringUtils.hasText(secret)) return secret;
      } else {
        log.warn("[BILL][HTTP][PI] status={} body={}", res.statusCode(), res.body());
      }
    } catch (Exception e) {
      log.warn("[BILL][HTTP][PI][ERR] {}", e.toString());
    }
    return null;
  }

  /** Se não houver PI (ex.: trial sem cobrança inicial), pega o pending_setup_intent da assinatura. */
  private String fetchSetupIntentSecretHttp(String subscriptionId) {
    if (!StringUtils.hasText(subscriptionId)) return null;
    try {
      final String url = "https://api.stripe.com/v1/subscriptions/" +
          URLEncoder.encode(subscriptionId, StandardCharsets.UTF_8) +
          "?expand[]=pending_setup_intent";

      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", "Bearer " + stripeSecretKey)
          .header("User-Agent", "ars-payment-service/1.0")
          .header("Content-Type", "application/x-www-form-urlencoded")
          .GET();

      if (StringUtils.hasText(httpApiVersion)) {
        b.header("Stripe-Version", httpApiVersion);
      }

      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 200 && res.statusCode() < 300) {
        JsonNode root = json.readTree(res.body());
        JsonNode siNode = root.get("pending_setup_intent");
        if (siNode != null) {
          if (siNode.isObject()) {
            String secret = textOrNull(siNode.get("client_secret"));
            if (StringUtils.hasText(secret)) return secret;
          } else if (siNode.isTextual()) {
            return fetchSetupIntentSecretByIdHttp(siNode.asText());
          }
        }
      } else {
        log.warn("[BILL][HTTP][SUB] status={} body={}", res.statusCode(), res.body());
      }
    } catch (Exception e) {
      log.warn("[BILL][HTTP][SUB][ERR] {}", e.toString());
    }
    return null;
  }

  private String fetchSetupIntentSecretByIdHttp(String siId) {
    if (!StringUtils.hasText(siId)) return null;
    try {
      final String url = "https://api.stripe.com/v1/setup_intents/" +
          URLEncoder.encode(siId, StandardCharsets.UTF_8);

      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", "Bearer " + stripeSecretKey)
          .header("User-Agent", "ars-payment-service/1.0")
          .header("Content-Type", "application/x-www-form-urlencoded")
          .GET();

      if (StringUtils.hasText(httpApiVersion)) {
        b.header("Stripe-Version", httpApiVersion);
      }

      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 200 && res.statusCode() < 300) {
        JsonNode root = json.readTree(res.body());
        String secret = textOrNull(root.get("client_secret"));
        if (StringUtils.hasText(secret)) return secret;
      } else {
        log.warn("[BILL][HTTP][SI] status={} body={}", res.statusCode(), res.body());
      }
    } catch (Exception e) {
      log.warn("[BILL][HTTP][SI][ERR] {}", e.toString());
    }
    return null;
  }

  private static String textOrNull(JsonNode n) {
    return (n != null && !n.isNull()) ? n.asText(null) : null;
  }
}
