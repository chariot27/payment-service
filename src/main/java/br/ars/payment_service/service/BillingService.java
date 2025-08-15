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
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.SubscriptionCreateParams;
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

  /** Fluxo único: PaymentSheet (Google Pay/cartão) usando PaymentIntent da latest_invoice */
  @Transactional
  public SubscribeResponse startSubscription(SubscribeRequest req) throws StripeException {
    Stripe.apiKey = stripeSecretKey;

    final String userId = require(req.userId(), "userId");
    final String email = req.email();
    final String priceId = StringUtils.hasText(req.priceId()) ? req.priceId() : defaultBasicPriceId;
    final String stripeVersion = StringUtils.hasText(req.stripeVersion()) ? req.stripeVersion() : mobileApiVersionDefault;

    log.info("[BILL][FLOW] startSubscription (GPay) userId={}, email={}, priceId={}, stripeVersion={}",
        userId, email, priceId, stripeVersion);

    // 1) Customer por usuário
    final String customerId = billingCustomerService.findOrCreateCustomer(userId, email);

    // 2) Criar assinatura DEFAULT_INCOMPLETE para gerar PaymentIntent na fatura
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
        // Expandimos para reduzir roundtrips; ainda assim aplicaremos retries
        .addExpand("latest_invoice")
        .addExpand("latest_invoice.payment_intent")
        .build();

    final Subscription subscription = Subscription.create(params);
    final String subscriptionId = subscription.getId();

    // 3) Obter o client_secret do PaymentIntent com retries curtos (caso o PI ainda não esteja pronto)
    String paymentIntentClientSecret = fetchInvoicePIClientSecretWithRetry(subscription, Duration.ofSeconds(8));

    if (!StringUtils.hasText(paymentIntentClientSecret)) {
      log.warn("[BILL][FLOW] Não foi possível obter payment_intent.client_secret da assinatura {}", subscriptionId);
    }

    // 4) Ephemeral Key para o app — DE FATO usando setStripeVersion(...) (corrige 400)
    EphemeralKeyCreateParams ekParams = EphemeralKeyCreateParams.builder()
        .setCustomer(customerId)
        .setStripeVersion(stripeVersion)
        .build();
    EphemeralKey ek = EphemeralKey.create(ekParams);

    log.info("[BILL][FLOW][RES] subId={}, customerId={}, hasPI={}", subscriptionId, customerId, paymentIntentClientSecret != null);

    // Retorno para o app (PaymentSheet usa o PI client_secret)
    return new SubscribeResponse(
        stripePublishableKey,
        customerId,
        subscriptionId,
        paymentIntentClientSecret, // usado pelo PaymentSheet / Google Pay
        ek.getSecret(),
        null, // setupIntentClientSecret (não usado neste fluxo)
        null  // hostedInvoiceUrl (não aplicável)
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

  /** Chamado pelo StripeWebhookController ao processar eventos */
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

  // ---------------- internals ----------------

  /**
   * Faz polling por alguns segundos até a fatura ter um PaymentIntent com client_secret.
   */
  private String fetchInvoicePIClientSecretWithRetry(Subscription sub, Duration maxWait) throws StripeException {
    // 1) Tentativa rápida com o que já temos expandido
    String cs = tryExtractPIClientSecretFromSubscription(sub);
    if (StringUtils.hasText(cs)) return cs;

    // 2) Se não deu, tentamos reconsultar a fatura com expand=payment_intent e, se necessário, finalizar
    final long deadline = System.nanoTime() + maxWait.toNanos();
    int attempt = 0;

    while (System.nanoTime() < deadline) {
      attempt++;

      // Reobter invoiceId e buscar a fatura expandida
      String invoiceId = null;
      try { invoiceId = sub.getLatestInvoice(); } catch (Throwable ignored) {}
      if (!StringUtils.hasText(invoiceId)) {
        // Como fallback, recarrega a assinatura rapidamente (para atualizar latest_invoice)
        try {
          sub = Subscription.retrieve(sub.getId()); // sem expand aqui; vamos mirar na invoice
          invoiceId = sub.getLatestInvoice();
        } catch (Throwable t) {
          log.debug("[BILL][PI-RETRY] Falha ao recarregar assinatura: {}", t.getMessage());
        }
      }

      if (StringUtils.hasText(invoiceId)) {
        try {
          InvoiceRetrieveParams irp = InvoiceRetrieveParams.builder()
              .addExpand("payment_intent")
              .build();
          Invoice inv = Invoice.retrieve(invoiceId, irp, null);

          // Se a fatura estiver em draft, finalize para garantir PI
          try {
            String status = inv.getStatus();
            if ("draft".equalsIgnoreCase(status)) {
              inv = inv.finalizeInvoice();
            }
          } catch (Throwable ignored) {}

          // Extrai o client_secret (objeto expandido ou via id)
          cs = tryExtractPIClientSecretFromInvoice(inv);
          if (StringUtils.hasText(cs)) {
            log.info("[BILL][PI-RETRY] Obtido client_secret na tentativa {}", attempt);
            return cs;
          }
        } catch (Throwable t) {
          log.debug("[BILL][PI-RETRY] retrieve invoice {} falhou: {}", invoiceId, t.getMessage());
        }
      }

      // Backoff: 200ms, 400ms, 600ms, ... (limite ~1s)
      sleepQuiet(200L * Math.min(attempt, 5));
    }

    return null;
  }

  private static String tryExtractPIClientSecretFromSubscription(Subscription subscription) {
    try {
      Invoice inv = subscription.getLatestInvoiceObject();
      if (inv == null) return null;
      return tryExtractPIClientSecretFromInvoice(inv);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String tryExtractPIClientSecretFromInvoice(Invoice inv) throws StripeException {
    if (inv == null) return null;

    // 1) Se o objeto expandido existir
    try {
      Method mObj = Invoice.class.getMethod("getPaymentIntentObject");
      Object piObj = mObj.invoke(inv);
      if (piObj instanceof PaymentIntent) {
        String cs = ((PaymentIntent) piObj).getClientSecret();
        if (StringUtils.hasText(cs)) return cs;
      }
    } catch (Throwable ignored) {}

    // 2) Fallback: pegar o ID e fazer retrieve
    try {
      Method mId = Invoice.class.getMethod("getPaymentIntent"); // String id em muitas versões
      Object idObj = mId.invoke(inv);
      if (idObj instanceof String piId && StringUtils.hasText(piId)) {
        PaymentIntent pi = PaymentIntent.retrieve(piId);
        String cs = pi.getClientSecret();
        if (StringUtils.hasText(cs)) return cs;
      }
    } catch (Throwable ignored) {}

    return null;
    }

  private static void sleepQuiet(long millis) {
    try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
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
