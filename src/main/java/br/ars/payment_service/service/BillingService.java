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
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.SubscriptionCreateParams;
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

  /** Fluxo único: Cartão/Carteiras/Google Pay via PaymentSheet pagando a fatura inicial (DEFAULT_INCOMPLETE). */
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

    // 2) Criar assinatura em DEFAULT_INCOMPLETE para gerar a latest_invoice com PaymentIntent
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
        // >>> IMPORTANTE: expandir o PI ligado à fatura
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.payment_intent")
        // <<<
        .build();

    final Subscription subscription = Subscription.create(params);
    final String subscriptionId = subscription.getId();

    // 3) Extrair client secret (várias estratégias compatíveis com versões do stripe-java)
    String paymentIntentClientSecret = null;

    Invoice inv = null;
    try { inv = subscription.getLatestInvoiceObject(); } catch (Throwable ignored) {}
    if (inv == null) {
      try {
        String invId = subscription.getLatestInvoice();
        if (StringUtils.hasText(invId)) {
          inv = Invoice.retrieve(invId);
        }
      } catch (Throwable ignored) {}
    }

    if (inv != null) {
      // 3.1) Tenta confirmation_secret (se o campo existir para sua versão de API/conta)
      try {
        Invoice.ConfirmationSecret cs = inv.getConfirmationSecret();
        if (cs != null && StringUtils.hasText(cs.getClientSecret())) {
          paymentIntentClientSecret = cs.getClientSecret();
        }
      } catch (Throwable ignored) {}

      // 3.2) Fallback: tentar inv.getPaymentIntentObject().getClientSecret() via reflection
      if (paymentIntentClientSecret == null) {
        try {
          Object piObj = tryInvokeNoArgs(inv, "getPaymentIntentObject");
          if (piObj instanceof PaymentIntent) {
            String cs = ((PaymentIntent) piObj).getClientSecret();
            if (StringUtils.hasText(cs)) {
              paymentIntentClientSecret = cs;
            }
          }
        } catch (Throwable ignored) {}
      }

      // 3.3) Fallback final: tentar inv.getPaymentIntent() (id) -> PaymentIntent.retrieve(id)
      if (paymentIntentClientSecret == null) {
        try {
          Object idObj = tryInvokeNoArgs(inv, "getPaymentIntent");
          if (idObj instanceof String) {
            String piId = (String) idObj;
            if (StringUtils.hasText(piId)) {
              PaymentIntent pi = PaymentIntent.retrieve(piId);
              if (pi != null && StringUtils.hasText(pi.getClientSecret())) {
                paymentIntentClientSecret = pi.getClientSecret();
              }
            }
          }
        } catch (Throwable ignored) {}
      }
    }

    // 4) Ephemeral Key (no servidor) para o app mobile
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

  /** Mantém compatibilidade com o controller atual. */
  public SubscriptionStatusResponse getStatusAndUpsert(String subscriptionId) throws StripeException {
    // Se precisar, faça upsert em DB aqui. Por ora, retornamos direto do Stripe.
    return getStatus(subscriptionId);
  }

  public SubscriptionStatusResponse getStatus(String subscriptionId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;
    final Subscription sub = Subscription.retrieve(subscriptionId);
    final SubscriptionBackendStatus status = mapStatus(sub);

    // Alguns jars do stripe-java não expõem getCurrentPeriodEnd(); retornamos nulo com segurança.
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

  /** Reflection “safe”: chama método sem argumentos, se existir. */
  private static Object tryInvokeNoArgs(Object target, String method) {
    if (target == null) return null;
    try {
      Method m = target.getClass().getMethod(method);
      m.setAccessible(true);
      return m.invoke(target);
    } catch (Throwable ignored) {
      return null;
    }
  }
}
