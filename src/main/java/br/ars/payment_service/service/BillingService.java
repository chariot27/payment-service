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
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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

  @Transactional
  public SubscribeResponse startSubscription(SubscribeRequest req) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final String userId = require(req.userId(), "userId");
    final String email = req.email();
    final String priceId = StringUtils.hasText(req.priceId()) ? req.priceId() : defaultBasicPriceId;
    final String stripeVersion = StringUtils.hasText(req.stripeVersion()) ? req.stripeVersion() : mobileApiVersionDefault;

    // "auto" (cartões/carteiras/Link) ou "boleto"
    final String pmMode = StringUtils.hasText(req.pmMode()) ? req.pmMode() : "auto";
    final boolean isBoleto = "boleto".equalsIgnoreCase(pmMode);

    log.info("[BILL][FLOW] startSubscription userId={}, email={}, priceId={}, pmMode={}, stripeVersion={}",
        userId, email, priceId, pmMode, stripeVersion);

    // 1) Customer único por userId
    final String customerId = billingCustomerService.findOrCreateCustomer(userId, email);

    // 2) Monta Subscription conforme o método de pagamento
    SubscriptionCreateParams.Builder sb = SubscriptionCreateParams.builder()
        .setCustomer(customerId)
        .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
        .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
        // Expansões úteis (quando existirem)
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.payment_intent")
        .addExpand("pending_setup_intent");

    if (isBoleto) {
      // Fatura para boleto; não salva PM para futuras cobranças
      sb.setCollectionMethod(SubscriptionCreateParams.CollectionMethod.SEND_INVOICE)
        .setDaysUntilDue(3L)
        .putExtraParam("payment_settings[payment_method_types][]", "boleto")
        .putExtraParam("payment_settings[payment_method_options][boleto][expires_after_days]", 3);
    } else {
      // Auto-cobrança: salvar PM para futuras cobranças
      sb.setCollectionMethod(SubscriptionCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
        .setPaymentSettings(
            SubscriptionCreateParams.PaymentSettings.builder()
                .setSaveDefaultPaymentMethod(
                    SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                )
                .build()
        )
        // Trial: coleta PM via SetupIntent (sem cobrança agora)
        .setTrialPeriodDays(30L);

      // NÃO enviar "payment_settings[automatic_payment_methods]" em Subscription
      // (parâmetro do PaymentIntent; aqui causava "parameter_unknown")
    }

    // Idempotência por usuário/price/modo para evitar duplicações
    RequestOptions ro = RequestOptions.builder()
        .setIdempotencyKey("sub-" + userId + "-" + priceId + "-" + pmMode)
        .build();

    final Subscription subscription = Subscription.create(sb.build(), ro);
    final String subscriptionId = subscription.getId();

    // 3) Monta dados para o app
    String paymentIntentClientSecret = null; // cobrança imediata (não usada no trial/auto)
    String setupIntentClientSecret = null;   // para salvar PM no trial
    String hostedInvoiceUrl = null;          // URL de boleto

    // (A) latest_invoice (objeto expandido ou via ID)
    Invoice inv = null;
    try { inv = subscription.getLatestInvoiceObject(); } catch (Throwable ignored) {}
    if (inv == null) {
      try {
        String invId = subscription.getLatestInvoice();
        if (StringUtils.hasText(invId)) inv = Invoice.retrieve(invId);
      } catch (Throwable ignored) {}
    }
    if (inv != null) {
      try { hostedInvoiceUrl = inv.getHostedInvoiceUrl(); } catch (Throwable ignored) {}
      try {
        String piId = inv.getPaymentIntent();
        if (StringUtils.hasText(piId)) {
          PaymentIntent pi = PaymentIntent.retrieve(piId);
          if (pi != null) paymentIntentClientSecret = pi.getClientSecret();
        }
      } catch (Throwable ignored) {}
    }

    // (B) pending_setup_intent (objeto expandido ou via ID)
    SetupIntent pendingSi = null;
    try { pendingSi = subscription.getPendingSetupIntentObject(); } catch (Throwable ignored) {}
    if (pendingSi == null) {
      try {
        String siId = subscription.getPendingSetupIntent();
        if (StringUtils.hasText(siId)) pendingSi = SetupIntent.retrieve(siId);
      } catch (Throwable ignored) {}
    }
    if (pendingSi != null) {
      try { setupIntentClientSecret = pendingSi.getClientSecret(); } catch (Throwable ignored) {}
    }

    // (C) Fallback: se tem trial e não veio SI, criamos um manualmente
    boolean hasTrial = false;
    try { hasTrial = subscription.getTrialEnd() != null; } catch (Throwable ignored) {}
    if (!isBoleto && hasTrial && setupIntentClientSecret == null) {
      SetupIntent si = SetupIntent.create(
          SetupIntentCreateParams.builder()
              .setCustomer(customerId)
              .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
              .build()
      );
      setupIntentClientSecret = si.getClientSecret();
    }

    // 4) Ephemeral Key para o app móvel — versão vem do app
    final EphemeralKeyCreateParams ekParams = EphemeralKeyCreateParams.builder()
        .setCustomer(customerId)
        .setStripeVersion(stripeVersion)
        .build();
    final EphemeralKey ek = EphemeralKey.create(ekParams);

    log.info("[BILL][FLOW][RES] subId={}, customerId={}, hasPI={}, hasPendingSI={}, hostedUrl?={}",
        subscriptionId, customerId, paymentIntentClientSecret != null, setupIntentClientSecret != null,
        hostedInvoiceUrl != null);

    // ⚠️ Construtor com 7 argumentos (inclui hostedInvoiceUrl)
    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        paymentIntentClientSecret,
        ek.getSecret(),
        setupIntentClientSecret,
        hostedInvoiceUrl
    );
  }

  public SubscriptionStatusResponse getStatus(String subscriptionId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;
    final Subscription sub = Subscription.retrieve(subscriptionId);
    final SubscriptionBackendStatus status = mapStatus(sub);

    String currentPeriodEndIso = null;
    boolean cancelAtPeriodEnd = false;
    try { cancelAtPeriodEnd = Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()); }
    catch (Throwable ignored) {}

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
}
