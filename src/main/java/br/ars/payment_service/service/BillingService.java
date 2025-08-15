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
    final String stripeVersion = StringUtils.hasText(req.stripeVersion())
        ? req.stripeVersion() : mobileApiVersionDefault;

    // "auto" (cartões/carteiras/link) ou "boleto"
    final String pmMode = StringUtils.hasText(req.pmMode()) ? req.pmMode() : "auto";
    final boolean isBoleto = "boleto".equalsIgnoreCase(pmMode);

    log.info("[BILL][FLOW] startSubscription userId={}, email={}, priceId={}, pmMode={}, stripeVersion={}",
        userId, email, priceId, pmMode, stripeVersion);

    // 1) Customer único por userId
    final String customerId = billingCustomerService.findOrCreateCustomer(userId, email);

    // 2) Monta Subscription Create Params de acordo com o método de pagamento
    SubscriptionCreateParams.Builder sb = SubscriptionCreateParams.builder()
        .setCustomer(customerId)
        .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
        // cria como incomplete para permitir confirmação no front
        .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
        // expansões úteis; vamos tratar com fallback mesmo sem expand
        .addExpand("latest_invoice")
        .addExpand("pending_setup_intent");

    if (isBoleto) {
      // Boleto por fatura (não salva PM para futuras cobranças automaticamente)
      sb.setCollectionMethod(SubscriptionCreateParams.CollectionMethod.SEND_INVOICE)
        .setDaysUntilDue(3L) // prazo do boleto/fatura
        // restringe tipos a boleto e ajusta opção de expiração
        .putExtraParam("payment_settings[payment_method_types][]", "boleto")
        .putExtraParam("payment_settings[payment_method_options][boleto][expires_after_days]", 3);
      // Geralmente sem trial com boleto; se quiser trial, descomente:
      // sb.setTrialPeriodDays(30L);
    } else {
      // Cobrança automática (cartão/Apple Pay/Google Pay/Link) com trial e salvamento do PM
      sb.setCollectionMethod(SubscriptionCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
        .setPaymentSettings(
            SubscriptionCreateParams.PaymentSettings.builder()
                .setSaveDefaultPaymentMethod(
                    SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                )
                .build()
        )
        // trial de 30 dias sem cobrança agora; Stripe cria pending_setup_intent
        .setTrialPeriodDays(30L);
      // Observação: não fixamos payment_method_types; deixamos o Stripe determinar (evita erros)
    }

    // Idempotência por usuário/price/mode evita duplicar assinatura
    RequestOptions subRO = RequestOptions.builder()
        .setIdempotencyKey("sub-" + userId + "-" + priceId + "-" + pmMode)
        .build();

    final Subscription subscription = Subscription.create(sb.build(), subRO);
    final String subscriptionId = subscription.getId();

    // 3) Obter secrets p/ front (compatível com stripe-java 29.x)
    String paymentIntentClientSecret = null;
    String setupIntentClientSecret  = null;

    // --- latest invoice (expandida ou não) ---
    Invoice inv = null;
    try { inv = subscription.getLatestInvoiceObject(); } catch (Throwable ignored) { }
    if (inv == null) {
      String invId = null;
      try { invId = subscription.getLatestInvoice(); } catch (Throwable ignored) { }
      if (StringUtils.hasText(invId)) {
        inv = Invoice.retrieve(invId);
      }
    }

    if (inv != null) {
      // Em 29.x, Invoice#getPaymentIntent() retorna o ID; recupere para obter o client_secret
      String piId = null;
      try { piId = inv.getPaymentIntent(); } catch (Throwable ignored) { }
      if (StringUtils.hasText(piId)) {
        PaymentIntent pi = PaymentIntent.retrieve(piId);
        paymentIntentClientSecret = pi.getClientSecret();
      }
    }

    // --- pending_setup_intent (expandido ou não) ---
    SetupIntent pendingSiObj = null;
    try { pendingSiObj = subscription.getPendingSetupIntentObject(); } catch (Throwable ignored) { }

    if (pendingSiObj != null) {
      setupIntentClientSecret = pendingSiObj.getClientSecret();
    } else {
      String pendingSiId = null;
      try { pendingSiId = subscription.getPendingSetupIntent(); } catch (Throwable ignored) { }
      if (StringUtils.hasText(pendingSiId)) {
        SetupIntent si = SetupIntent.retrieve(pendingSiId);
        setupIntentClientSecret = si.getClientSecret();
      }
    }

    // 4) Ephemeral Key (para mobile) – via header de versão
    final EphemeralKeyCreateParams ekParams = EphemeralKeyCreateParams.builder()
        .setCustomer(customerId)
        .build();
    final RequestOptions ekRO = RequestOptions.builder()
        .setStripeVersionOverride(stripeVersion) // versão da API usada no app
        .build();
    final EphemeralKey ek = EphemeralKey.create(ekParams, ekRO);

    log.info("[BILL][FLOW][RES] subId={}, customerId={}, hasPI={}, hasPendingSI={}",
        subscriptionId, customerId, paymentIntentClientSecret != null, setupIntentClientSecret != null);

    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        paymentIntentClientSecret, // para confirmar pagamento imediato (se existir)
        ek.getSecret(),            // ephemeral key para SDK mobile
        setupIntentClientSecret    // para salvar PM durante o trial (auto)
    );
  }

  public SubscriptionStatusResponse getStatus(String subscriptionId) throws StripeException {
    Stripe.apiKey = stripeSecretKey;
    final Subscription sub = Subscription.retrieve(subscriptionId);
    final SubscriptionBackendStatus status = mapStatus(sub);

    String currentPeriodEndIso = null; // manter null se não disponível
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
