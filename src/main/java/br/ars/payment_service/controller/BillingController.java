package br.ars.payment_service.controller;

import br.ars.payment_service.dto.ChangePlanRequest;
import br.ars.payment_service.dto.SubscribeRequest;
import br.ars.payment_service.dto.SubscribeResponse;
import br.ars.payment_service.dto.SubscriptionStatusResponse;
import br.ars.payment_service.service.BillingService;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST de billing/assinaturas.
 *
 * Convenções importantes para “virar” ATIVA/TRIALING:
 * - startSubscription deve criar a assinatura com payment_behavior=default_incomplete (quando cobrar agora),
 *   expandindo latest_invoice.payment_intent e retornando paymentIntentClientSecret.
 * - O app confirma via PaymentSheet.
 * - O webhook (acima) persiste a transição de status (invoice.payment_succeeded / customer.subscription.updated).
 * - getStatus pode ler direto do Stripe ou do cache/banco que o webhook atualiza.
 */
@RestController
@RequestMapping(path = "/api/billing", produces = MediaType.APPLICATION_JSON_VALUE)
public class BillingController {

  private static final Logger log = LoggerFactory.getLogger(BillingController.class);

  private final BillingService billingService;

  public BillingController(BillingService billingService) {
    this.billingService = billingService;
  }

  /** Inicia a assinatura e devolve os segredos para a PaymentSheet (PI ou SI, conforme o fluxo). */
  @PostMapping(path = "/subscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscribeResponse> subscribe(@RequestBody SubscribeRequest request) throws StripeException {
    // O service deve:
    // - Garantir o customer (por userId/email)
    // - Criar a assinatura usando DEFAULT_INCOMPLETE + expand=latest_invoice.payment_intent (ou SetupIntent para trial)
    // - Gerar EphemeralKey (respeitando Stripe-Version do mobile, se aplicável)
    // - Retornar: subscriptionId, customerId, ephemeralKeySecret, paymentIntentClientSecret OU setupIntentClientSecret, publishableKey
    SubscribeResponse res = billingService.startSubscription(request);
    return ResponseEntity.ok(res);
  }

  /** Consulta status atual da assinatura (ACTIVE/TRIALING/INCOMPLETE/PAST_DUE/etc). */
  @GetMapping("/subscriptions/{id}")
  public ResponseEntity<SubscriptionStatusResponse> getStatus(@PathVariable("id") String subscriptionId) throws StripeException {
    // O service pode:
    // - Ler do Stripe (fonte da verdade) e mapear para seu enum
    // - OU ler do seu banco (que o webhook mantém em sincronia) — recomendável ter ambos e preferir Stripe quando preciso
    SubscriptionStatusResponse res = billingService.getStatus(subscriptionId);
    return ResponseEntity.ok(res);
  }

  /** Troca o plano (price) de uma assinatura, com proration configurável. */
  @PostMapping(path = "/change-plan", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> changePlan(@RequestBody ChangePlanRequest req) throws StripeException {
    billingService.changePlan(req.subscriptionId(), req.newPriceId(), req.prorationBehavior());
    return ResponseEntity.noContent().build();
  }

  /* -------------------- Handlers uniformes de erro -------------------- */

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorBody> onBadRequest(IllegalArgumentException ex) {
    log.warn("[BILL][400] {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorBody(ex.getMessage()));
  }

  @ExceptionHandler(StripeException.class)
  public ResponseEntity<ErrorBody> onStripe(StripeException ex) {
    log.error("[BILL][Stripe] {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorBody(ex.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorBody> onGeneric(Exception ex) {
    log.error("[BILL][500] {}", ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorBody("Internal error"));
  }

  public record ErrorBody(String message) {}
}
