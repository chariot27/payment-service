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
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.SubscriptionUpdateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
   *  - Cria Subscription com DEFAULT_INCOMPLETE + expand latest_invoice.payment_intent
   *  - Faz polling curto até o invoice ter payment_intent com client_secret
   *  - Retorna o PI client_secret para a PaymentSheet
   *  - NÃO cai para SetupIntent (evita confundir status como INCOMPLETE)
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
      // Se quiser manter fallback para SetupIntent (não recomendado para “sem trial”), descomente:
      // String si = createSetupIntentCs(customerId);
      // return new SubscribeResponse(stripePublishableKey, customerId, subscriptionId, null, createEphemeralKey(customerId, stripeVersion), si, null);
      throw new IllegalStateException("Falha ao preparar pagamento inicial. Tente novamente.");
    }

    // 4) Ephemeral Key na versão do mobile
    final String ephKeySecret = createEphemeralKey(customerId, stripeVersion);

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

  /** Consulta status atual no Stripe e devolve DTO (pode opcionalmente upsert no DB se desejar). */
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
      Long epoch = sub.getCurrentPeriodEnd();
      if (epoch != null) {
        currentPeriodEndIso = Instant.ofEpochSecond(epoch)
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      }
    } catch (Throwable ignored) {}

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

  /** Chamada pelo WebhookController: persiste o status (ACTIVE/TRIALING/etc.) no seu banco. */
  public void applyWebhookUpdate(Subscription sub, Invoice inv) {
    try {
      final String subIdSafe = (sub != null) ? sub.getId() : null;
      final String invId = (inv != null) ? inv.getId() : null;
      final SubscriptionBackendStatus status = (sub != null) ? mapStatus(sub) : SubscriptionBackendStatus.INACTIVE;
      log.info("[BILL][WEBHOOK] subscriptionId={}, status={}, invoiceId={}", subIdSafe, status, invId);

      // TODO: upsert no seu banco (userId ↔ subscriptionId), datas (current_period_end), cancelAtPeriodEnd etc.
      // ex.: subscriptionRepo.upsert(...)

    } catch (Exception e) {
      log.error("[BILL][WEBHOOK][ERR] {}", e.getMessage(), e);
    }
  }

  // ====================== Internals ======================

  /**
   * Tenta obter o client_secret do PaymentIntent da invoice inicial (com retry até maxWait).
   * Estratégia:
   *   - Recarrega a subscription com expand latest_invoice.payment_intent
   *   - Se não vier, busca o latest_invoice diretamente com expand=payment_intent
   *   - Retorna client_secret assim que aparecer
   */
  private String fetchPIClientSecretBlocking(Subscription initial, Duration maxWait) throws StripeException {
    final long deadline = System.nanoTime() + maxWait.toNanos();
    int attempt = 0;

    Subscription sub = initial;

    while (System.nanoTime() < deadline) {
      attempt++;

      // (A) Tenta via subscription expand
      try {
        sub = Subscription.retrieve(
            sub.getId(),
            SubscriptionRetrieveParams.builder()
                .addExpand("latest_invoice")
                .addExpand("latest_invoice.payment_intent")
                .build(),
            null
        );
        Invoice inv = sub.getLatestInvoiceObject();
        if (inv != null) {
          PaymentIntent pi = inv.getPaymentIntent();
          if (pi != null && StringUtils.hasText(pi.getClientSecret())) {
            log.info("[BILL][PI] CS via subscription expand (tentativa {})", attempt);
            return pi.getClientSecret();
          }
        }
      } catch (Throwable t) {
        log.debug("[BILL][PI] subscription expand falhou: {}", t.getMessage());
      }

      // (B) Tenta via retrieve direto da invoice com expand=payment_intent
      try {
        String invId = sub.getLatestInvoice();
        if (StringUtils.hasText(invId)) {
          Invoice inv = Invoice.retrieve(
              invId,
              InvoiceRetrieveParams.builder().addExpand("payment_intent").build(),
              null
          );
          if (inv != null) {
            PaymentIntent pi = inv.getPaymentIntent();
            if (pi != null && StringUtils.hasText(pi.getClientSecret())) {
              log.info("[BILL][PI] CS via invoice expand (tentativa {})", attempt);
              return pi.getClientSecret();
            }
          }
        }
      } catch (Throwable t) {
        log.debug("[BILL][PI] invoice expand falhou: {}", t.getMessage());
      }

      // (C) Aguardar um pouco e repetir
      long backoffMs = Math.min(2000L, 250L * attempt);
      try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    log.warn("[BILL][PI-RETRY] Timeout sem obter client_secret para sub {}", initial.getId());
    return null;
  }

  private String createEphemeralKey(String customerId, String stripeVersion) throws StripeException {
    EphemeralKeyCreateParams params = EphemeralKeyCreateParams.builder()
        .setCustomer(customerId)
        .build();

    // A maneira suportada para “forçar” a versão do mobile é via RequestOptions.setStripeVersionOverride
    RequestOptions ro = RequestOptions.builder()
        .setStripeVersionOverride(stripeVersion)
        .build();

    EphemeralKey ek = EphemeralKey.create(params, ro);
    if (ek == null || !StringUtils.hasText(ek.getSecret())) {
      throw new IllegalStateException("Falha ao criar EphemeralKey");
    }
    return ek.getSecret();
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
