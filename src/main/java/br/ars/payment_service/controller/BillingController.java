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

@RestController
@RequestMapping(path = "/api/billing", produces = MediaType.APPLICATION_JSON_VALUE)
public class BillingController {
  private static final Logger log = LoggerFactory.getLogger(BillingController.class);

  private final BillingService billingService;

  public BillingController(BillingService billingService) {
    this.billingService = billingService;
  }

  /** POST /api/billing/subscribe (SEM TRIAL) */
  @PostMapping(path = "/subscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscribeResponse> subscribe(@RequestBody SubscribeRequest request) throws StripeException {
    // Service cria assinatura com DEFAULT_INCOMPLETE + expand PI, gera EphemeralKey e retorna client_secret do PI.
    SubscribeResponse res = billingService.startSubscription(request);
    return ResponseEntity.ok(res);
  }

  /** GET /api/billing/subscriptions/{id} — consulta Stripe e (opcional) upsert local */
  @GetMapping("/subscriptions/{id}")
  public ResponseEntity<SubscriptionStatusResponse> getStatus(@PathVariable("id") String subscriptionId) throws StripeException {
    // Importante: implemente no service uma leitura direta no Stripe (fonte da verdade)
    // e faça upsert no banco para “destravamento” imediato, mesmo sem o webhook ter chegado ainda.
    SubscriptionStatusResponse res = billingService.getStatusAndUpsert(subscriptionId);
    return ResponseEntity.ok(res);
  }

  /** Troca de plano */
  @PostMapping(path = "/change-plan", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> changePlan(@RequestBody ChangePlanRequest req) throws StripeException {
    billingService.changePlan(req.subscriptionId(), req.newPriceId(), req.prorationBehavior());
    return ResponseEntity.noContent().build();
  }

  /* ---- Handlers uniformes ---- */

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
