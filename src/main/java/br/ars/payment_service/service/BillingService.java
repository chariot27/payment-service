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
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.SetupIntentCreateParams;
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

@Service
public class BillingService {

  private static final Logger log = LoggerFactory.getLogger(BillingService.class);

  @Value("${app.stripe.secret-key}")
  private String stripeSecretKey;

  @Value("${app.stripe.publishable-key}")
  private String stripePublishableKey;

  @Value("${app.stripe.prices.basic}")
  private String defaultBasicPriceId;

  /** Versão da API usada pelo app móvel ao criar EphemeralKey (ex.: 2020-08-27) */
  @Value("${app.stripe.mobile-api-version:2020-08-27}")
  private String mobileApiVersionDefault;

  private final BillingCustomerService billingCustomerService;

  public BillingService(BillingCustomerService billingCustomerService) {
    this.billingCustomerService = billingCustomerService;
  }

  /** Fluxo: PaymentSheet (Google Pay/cartão) com PI da latest_invoice; fallback para SI em cenários sem PI. */
  @Transactional
  public SubscribeResponse startSubscription(SubscribeRequest req) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final String userId = require(req.userId(), "userId");
    final String email = req.email();
    final String priceId = StringUtils.hasText(req.priceId()) ? req.priceId() : defaultBasicPriceId;
    final String stripeVersion = StringUtils.hasText(req.stripeVersion()) ? req.stripeVersion() : mobileApiVersionDefault;

    log.info("[BILL][FLOW] startSubscription (GPay) userId={}, email={}, priceId={}, stripeVersion={}",
        userId, email, priceId, stripeVersion);

    // 1) Customer único por usuário
    final String customerId = billingCustomerService.findOrCreateCustomer(userId, email);

    // 2) Criar assinatura DEFAULT_INCOMPLETE → gera invoice e (quando > 0) cria PaymentIntent na latest_invoice
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
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.payment_intent")
        .build();

    Subscription subscription = Subscription.create(params);
    final String subscriptionId = subscription.getId();

    // 3) Tentar obter o PI client_secret com polling/backoff (cobre latência e invoice em draft)
    PIResult pi = fetchPIClientSecretWithRetry(subscription, Duration.ofSeconds(18));

    String paymentIntentClientSecret = pi.clientSecret;

    // 4) Se não tem PI (valor 0, draft não finalizado, etc.), criamos um SetupIntent para coletar método de pagamento
    String setupIntentClientSecret = null;
    if (!StringUtils.hasText(paymentIntentClientSecret)) {
      setupIntentClientSecret = createSetupIntentCs(customerId);
      log.info("[BILL][FLOW] Fallback para SetupIntent (invoiceZero? {}, draft? {}), sub={}",
          pi.invoiceZero, pi.invoiceDraft, subscriptionId);
    }

    // 5) Ephemeral Key para o app (usa a mesma versão do SDK mobile)
    EphemeralKey ek = EphemeralKey.create(
        EphemeralKeyCreateParams.builder()
            .setCustomer(customerId)
            .setStripeVersion(stripeVersion)
            .build()
    );

    log.info("[BILL][FLOW][RES] subId={}, customerId={}, hasPI={}, hasSI={}",
        subscriptionId, customerId, paymentIntentClientSecret != null, setupIntentClientSecret != null);

    // Retorno: PaymentSheet aceita PI (pagar agora) OU SI (salvar PM p/ futuras cobranças)
    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        paymentIntentClientSecret, // pode ser null se não houver cobrança agora
        ek.getSecret(),
        setupIntentClientSecret,   // preenchido no fallback
        null
    );
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

  /** Usado pelo StripeWebhookController (log/persistência futura). */
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

  // ===================== internals =====================

  /** Resultado detalhado do probe de PI. */
  private static final class PIResult {
    final String clientSecret; final boolean invoiceZero; final boolean invoiceDraft;
    PIResult(String cs, boolean zero, boolean draft) { this.clientSecret = cs; this.invoiceZero = zero; this.invoiceDraft = draft; }
  }

  /**
   * Faz polling por alguns segundos até a fatura ter um PaymentIntent com client_secret.
   * Cobre:
   *  - latência (PI ainda não anexado à invoice),
   *  - invoice em draft (tenta finalizeInvoice uma vez),
   *  - invoice de valor 0 (não haverá PI).
   */
  private PIResult fetchPIClientSecretWithRetry(Subscription sub, Duration maxWait) throws StripeException {
    final long deadline = System.nanoTime() + maxWait.toNanos();
    int attempt = 0;
    boolean triedFinalize = false;
    boolean sawDraft = false;

    while (System.nanoTime() < deadline) {
      attempt++;

      // Recarregar assinatura com expands (não confiar no objeto em memória)
      try {
        sub = Subscription.retrieve(
            sub.getId(),
            SubscriptionRetrieveParams.builder()
                .addExpand("latest_invoice")
                .addExpand("latest_invoice.payment_intent")
                .build(),
            null
        );
      } catch (Throwable t) {
        log.debug("[BILL][PI-RETRY] reload sub failed: {}", t.getMessage());
      }

      // Tentar extrair do expand
      Invoice inv = safeGetLatestInvoice(sub);
      String cs = tryExtractPIClientSecretFromInvoice(inv);
      if (StringUtils.hasText(cs)) {
        log.info("[BILL][PI-RETRY] client_secret obtido (expand) na tentativa {}", attempt);
        return new PIResult(cs, false, false);
      }

      // Se tiver invoice, analisar status/valores
      if (inv != null) {
        String invStatus = safeGetStatus(inv);
        sawDraft = "draft".equalsIgnoreCase(invStatus) || sawDraft;
        Long amountDue = safeGetAmountDue(inv);
        Long total = safeGetTotal(inv);

        // Valor 0 → não haverá PI (cupom/trial/desconto/meters=0)
        if (isZero(amountDue) || isZero(total)) {
          log.info("[BILL][PI-RETRY] invoice valor 0 (amount_due={}, total={}), sem PI; usar SetupIntent.", amountDue, total);
          return new PIResult(null, true, "draft".equalsIgnoreCase(invStatus));
        }

        // Se está draft, tentar finalizar UMA vez (ex.: tax pendente); depois re-tentar extrair
        if ("draft".equalsIgnoreCase(invStatus) && !triedFinalize) {
          triedFinalize = true;
          try {
            inv = inv.finalizeInvoice();
            cs = tryExtractPIClientSecretFromInvoice(inv);
            if (StringUtils.hasText(cs)) {
              log.info("[BILL][PI-RETRY] client_secret após finalizeInvoice (tentativa {})", attempt);
              return new PIResult(cs, false, true);
            }
          } catch (Throwable t) {
            log.debug("[BILL][PI-RETRY] finalizeInvoice falhou: {}", t.getMessage());
          }
        }
      }

      // Backoff com jitter (sobe até ~2s) e continua
      long base = 250L * Math.min(attempt, 8);
      sleepQuiet(base + (long)(Math.random() * 150));
    }

    log.warn("[BILL][PI-RETRY] Timeout sem obter client_secret para sub {}", sub.getId());
    return new PIResult(null, false, sawDraft);
  }

  /** Cria SetupIntent OFF_SESSION para salvar PM e cobrar no próximo ciclo/fatura > 0. */
  private String createSetupIntentCs(String customerId) throws StripeException {
    SetupIntent si = SetupIntent.create(
        SetupIntentCreateParams.builder()
            .setCustomer(customerId)
            .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
            .build()
    );
    return si.getClientSecret();
  }

  private static Invoice safeGetLatestInvoice(Subscription sub) {
    try {
      Invoice inv = sub.getLatestInvoiceObject();
      if (inv != null) return inv;
    } catch (Throwable ignored) {}
    try {
      String invId = sub.getLatestInvoice();
      if (StringUtils.hasText(invId)) return Invoice.retrieve(invId);
    } catch (Throwable ignored) {}
    return null;
  }

  private static String tryExtractPIClientSecretFromInvoice(Invoice inv) {
    if (inv == null) return null;

    // (A) Objeto expandido (se a tua stripe-java expõe getPaymentIntentObject())
    try {
      Method mObj = Invoice.class.getMethod("getPaymentIntentObject");
      Object piObj = mObj.invoke(inv);
      if (piObj instanceof PaymentIntent pi) {
        String cs = pi.getClientSecret();
        if (StringUtils.hasText(cs)) return cs;
      }
    } catch (Throwable ignored) {}

    // (B) Só o ID → retrieve do PI
    try {
      Method mId = Invoice.class.getMethod("getPaymentIntent"); // retorna String
      Object idObj = mId.invoke(inv);
      if (idObj instanceof String piId && StringUtils.hasText(piId)) {
        PaymentIntent pi = PaymentIntent.retrieve(piId);
        String cs = pi.getClientSecret();
        if (StringUtils.hasText(cs)) return cs;
      }
    } catch (Throwable ignored) {}

    return null;
  }

  private static void sleepQuiet(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  }

  private static String require(String v, String field) {
    if (!StringUtils.hasText(v)) throw new IllegalArgumentException(field + " é obrigatório");
    return v;
  }

  private static boolean isZero(Long v) { return v != null && v == 0L; }
  private static Long safeGetAmountDue(Invoice inv) { try { return inv.getAmountDue(); } catch (Throwable ignored) { return null; } }
  private static Long safeGetTotal(Invoice inv) { try { return inv.getTotal(); } catch (Throwable ignored) { return null; } }
  private static String safeGetStatus(Invoice inv) { try { return inv.getStatus(); } catch (Throwable ignored) { return null; } }

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
