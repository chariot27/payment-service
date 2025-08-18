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
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.PaymentIntentConfirmParams;
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

  private final BillingCustomerService billingCustomerService;

  public BillingService(BillingCustomerService billingCustomerService) {
    this.billingCustomerService = billingCustomerService;
  }

  /** Fluxo único: PaymentSheet cobrando a fatura inicial (DEFAULT_INCOMPLETE). */
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

    // 2) Criar assinatura com DEFAULT_INCOMPLETE e expandir o PaymentIntent da fatura
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
        .addExpand("latest_invoice.payment_intent")
        .build();

    final Subscription subscription = Subscription.create(params);
    final String subscriptionId = subscription.getId();

    // 3) Extrair client_secret do PaymentIntent (compatível com várias versões do SDK)
    String paymentIntentClientSecret = null;

    Invoice inv = safeGetLatestInvoice(subscription);
    if (inv == null) {
      // fallback: recuperar invoice com expand do payment_intent
      final String invId = subscription.getLatestInvoice();
      if (StringUtils.hasText(invId)) {
        final InvoiceRetrieveParams irp = InvoiceRetrieveParams.builder()
            .addExpand("payment_intent")
            .build();
        inv = Invoice.retrieve(invId, irp, (RequestOptions) null);
      }
    }
    paymentIntentClientSecret = tryExtractClientSecretFromInvoice(inv);

    // 4) Ephemeral Key para o app mobile
    final EphemeralKey ek = EphemeralKey.create(
        EphemeralKeyCreateParams.builder()
            .setCustomer(customerId)
            .setStripeVersion(stripeVersion)
            .build()
    );

    log.info("[BILL][FLOW][RES] subId={}, customerId={}, hasPI={}",
        subscriptionId, customerId, paymentIntentClientSecret != null);

    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        paymentIntentClientSecret, // PaymentSheet paga a fatura inicial
        ek.getSecret(),
        null, // setupIntentClientSecret (não usado neste fluxo)
        null  // hostedInvoiceUrl (não aplicável neste fluxo)
    );
  }

  /** Confirma manualmente o PaymentIntent inicial (opcional). */
  public void confirmInitialPayment(String subscriptionId, String paymentMethodId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final SubscriptionRetrieveParams srp = SubscriptionRetrieveParams.builder()
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

    String currentPeriodEndIso = null; // alguns jars não têm getter estável
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

  /** Para uso em webhooks (opcional). */
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

  // ---------------- helpers ----------------

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

  /** Tenta várias formas de obter o client_secret da fatura (compatível com SDKs diferentes). */
  private static String tryExtractClientSecretFromInvoice(Invoice inv) throws StripeException {
    if (inv == null) return null;

    // (A) Via confirmation_secret (existente em versões novas do SDK)
    try {
      Method mCs = inv.getClass().getMethod("getConfirmationSecret");
      Object cs = mCs.invoke(inv);
      if (cs != null) {
        Method mSecret = cs.getClass().getMethod("getClientSecret");
        Object secret = mSecret.invoke(cs);
        if (secret instanceof String s && StringUtils.hasText(s)) return s;
      }
    } catch (Throwable ignored) {}

    // (B) Via objeto PaymentIntent expandido (sem chamar diretamente getters inexistentes)
    try {
      Method mObj = inv.getClass().getMethod("getPaymentIntentObject");
      Object piObj = mObj.invoke(inv);
      if (piObj instanceof PaymentIntent pi) {
        String s = pi.getClientSecret();
        if (StringUtils.hasText(s)) return s;
      }
    } catch (Throwable ignored) {}

    // (C) Via id do PaymentIntent e retrieve
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

  /** Extrai o PaymentIntent ID para confirmações manuais. */
  private static String tryExtractPaymentIntentIdFromInvoice(Invoice inv) {
    if (inv == null) return null;
    // Primeiro tenta como id direto
    try {
      Method mId = inv.getClass().getMethod("getPaymentIntent");
      Object id = mId.invoke(inv);
      if (id instanceof String s && StringUtils.hasText(s)) return s;
    } catch (Throwable ignored) {}
    // Depois tenta como objeto expandido
    try {
      Method mObj = inv.getClass().getMethod("getPaymentIntentObject");
      Object piObj = mObj.invoke(inv);
      if (piObj instanceof PaymentIntent pi) {
        return pi.getId();
      }
    } catch (Throwable ignored) {}
    return null;
  }
}
