package br.ars.payment_service.service;

import br.ars.payment_service.dto.SubscribeRequest;
import br.ars.payment_service.dto.SubscribeResponse;
import br.ars.payment_service.dto.SubscriptionBackendStatus;
import br.ars.payment_service.dto.SubscriptionStatusResponse;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.EphemeralKey;
import com.stripe.model.Invoice;
import com.stripe.model.SetupIntent;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.EphemeralKeyCreateParams;
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

  /** Versão da API usada pelo SDK mobile (RN/Android/iOS) para EphemeralKey */
  @Value("${app.stripe.mobile-api-version:2020-08-27}")
  private String mobileApiVersionDefault;

  /** Versão da API a ser usada pelo servidor (override por request) */
  @Value("${app.stripe.api-version:2023-10-16}")
  private String serverApiVersion;

  private final BillingCustomerService billingCustomerService;

  public BillingService(BillingCustomerService billingCustomerService) {
    this.billingCustomerService = billingCustomerService;
  }

  /** RequestOptions padrão com override de versão para TODAS as chamadas do servidor */
  private RequestOptions ro() {
    return RequestOptions.builder()
        .setStripeVersion(serverApiVersion)
        .build();
  }

  @Transactional
  public SubscribeResponse startSubscription(SubscribeRequest req) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final String userId = require(req.userId(), "userId");
    final String email = req.email();
    final String priceId = StringUtils.hasText(req.priceId()) ? req.priceId() : defaultBasicPriceId;
    final String stripeVersionMobile = StringUtils.hasText(req.stripeVersion())
        ? req.stripeVersion() : mobileApiVersionDefault;

    // "auto" (cartões/carteiras/link) ou "boleto"
    final String pmMode = StringUtils.hasText(req.pmMode()) ? req.pmMode() : "auto";
    final boolean isBoleto = "boleto".equalsIgnoreCase(pmMode);

    log.info("[BILL][FLOW] startSubscription userId={}, email={}, priceId={}, pmMode={}, stripeVersion={}",
        userId, email, priceId, pmMode, stripeVersionMobile);

    // 1) Customer único por userId
    final String customerId = billingCustomerService.findOrCreateCustomer(userId, email);

    // 2) Monta Subscription Create Params conforme método de pagamento
    SubscriptionCreateParams.Builder sb = SubscriptionCreateParams.builder()
        .setCustomer(customerId)
        .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
        // cria como incomplete para permitir confirmação no front
        .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
        // expansões úteis
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.confirmation_secret") // <-- garante o client_secret no retorno
        .addExpand("pending_setup_intent");

    if (isBoleto) {
      // Boleto por fatura
      sb.setCollectionMethod(SubscriptionCreateParams.CollectionMethod.SEND_INVOICE)
        .setDaysUntilDue(3L)
        .putExtraParam("payment_settings[payment_method_types][]", "boleto")
        .putExtraParam("payment_settings[payment_method_options][boleto][expires_after_days]", 3);
    } else {
      // Cartão/Carteiras/Link com trial e salvamento do PM
      sb.setCollectionMethod(SubscriptionCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
        .setPaymentSettings(
            SubscriptionCreateParams.PaymentSettings.builder()
                .setSaveDefaultPaymentMethod(
                    SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                )
                .build()
        )
        .setTrialPeriodDays(30L);
    }

    // Idempotência + override de versão neste request de criação
    RequestOptions subRO = RequestOptions.builder()
        .setIdempotencyKey("sub-" + userId + "-" + priceId + "-" + pmMode)
        .setStripeVersion(serverApiVersion)
        .build();

    final Subscription subscription = Subscription.create(sb.build(), subRO);
    final String subscriptionId = subscription.getId();

    // 3) Obter secrets p/ front
    String paymentIntentClientSecret = null;
    String setupIntentClientSecret  = null;

    // --- latest invoice (expandida) ---
    Invoice inv = null;
    try { inv = subscription.getLatestInvoiceObject(); } catch (Throwable ignored) { }

    if (inv == null) {
      String invId = null;
      try { invId = subscription.getLatestInvoice(); } catch (Throwable ignored) { }
      if (StringUtils.hasText(invId)) {
        // garante mesma versão ao recuperar
        inv = Invoice.retrieve(invId, ro());
      }
    }

    if (inv != null) {
      // v29+: usar confirmation_secret para obter o client_secret do pagamento da invoice
      Invoice.ConfirmationSecret cs = null;
      try { cs = inv.getConfirmationSecret(); } catch (Throwable ignored) {}
      if (cs == null) {
        // fallback: tente expandir numa nova recuperação, caso o create não tenha expandido
        try {
          Invoice retrieved = Invoice.retrieve(inv.getId(),
              RequestOptions.builder().setStripeVersion(serverApiVersion).build());
          cs = retrieved.getConfirmationSecret();
        } catch (Throwable ignored) {}
      }
      if (cs != null && StringUtils.hasText(cs.getClientSecret())) {
        paymentIntentClientSecret = cs.getClientSecret();
      }
    }

    // --- pending_setup_intent (expandido ou pelo ID) ---
    SetupIntent pendingSiObj = null;
    try { pendingSiObj = subscription.getPendingSetupIntentObject(); } catch (Throwable ignored) { }

    if (pendingSiObj != null) {
      setupIntentClientSecret = pendingSiObj.getClientSecret();
    } else {
      String pendingSiId = null;
      try { pendingSiId = subscription.getPendingSetupIntent(); } catch (Throwable ignored) { }
      if (StringUtils.hasText(pendingSiId)) {
        SetupIntent si = SetupIntent.retrieve(pendingSiId, ro());
        setupIntentClientSecret = si.getClientSecret();
      }
    }

    // 4) Ephemeral Key (para mobile) – define a versão via params (não via header)
    final EphemeralKeyCreateParams ekParams = EphemeralKeyCreateParams.builder()
        .setCustomer(customerId)
        .setStripeVersion(stripeVersionMobile) // versão do SDK mobile
        .build();
    final EphemeralKey ek = EphemeralKey.create(ekParams);

    log.info("[BILL][FLOW][RES] subId={}, customerId={}, hasPI={}, hasPendingSI={}",
        subscriptionId, customerId, paymentIntentClientSecret != null, setupIntentClientSecret != null);

    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        paymentIntentClientSecret,
        ek.getSecret(),
        setupIntentClientSecret
    );
  }

  public SubscriptionStatusResponse getStatus(String subscriptionId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;
    final Subscription sub = Subscription.retrieve(subscriptionId, ro());
    final SubscriptionBackendStatus status = mapStatus(sub);

    String currentPeriodEndIso = null; // manter null se não disponível
    boolean cancelAtPeriodEnd = false;
    try { cancelAtPeriodEnd = Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()); }
    catch (Throwable ignored) {}

    return new SubscriptionStatusResponse(subscriptionId, status, currentPeriodEndIso, cancelAtPeriodEnd);
  }

  public void changePlan(String subscriptionId, String newPriceId, String prorationBehaviorRaw) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final Subscription sub = Subscription.retrieve(subscriptionId, ro());
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

    final Subscription updated = sub.update(b.build(), ro());
    log.info("[BILL][CHANGE_PLAN] subscriptionId={}, status={}", updated.getId(), updated.getStatus());
  }

  public void applyWebhookUpdate(Subscription sub, Invoice inv) {
    try {
      final String subIdSafe = (sub != null) ? sub.getId() : null;
      final String invId = (inv != null) ? inv.getId() : null;
      final SubscriptionBackendStatus status = (sub != null) ? mapStatus(sub) : SubscriptionBackendStatus.INACTIVE;
      log.info("[BILL][WEBHOOK] subscriptionId={}, status={}, invoiceId={}", subIdSafe, status, invId);
      // TODO: persistir em DB (status + datas), e publicar eventos internos
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
