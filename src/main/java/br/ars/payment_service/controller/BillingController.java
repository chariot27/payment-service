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

  /** POST /api/billing/subscribe */
  @PostMapping(path = "/subscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscribeResponse> subscribe(@RequestBody SubscribeRequest request) throws StripeException {
    SubscribeResponse res = billingService.startSubscription(request);
    return ResponseEntity.ok(res);
  }

  /** GET /api/billing/subscriptions/{id} */
  @GetMapping("/subscriptions/{id}")
  public ResponseEntity<SubscriptionStatusResponse> getStatus(@PathVariable("id") String subscriptionId) throws StripeException {
    SubscriptionStatusResponse res = billingService.getStatus(subscriptionId);
    return ResponseEntity.ok(res);
  }

  /** POST /api/billing/change-plan */
  @PostMapping(path = "/change-plan", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> changePlan(@RequestBody ChangePlanRequest req) throws StripeException {
    billingService.changePlan(req.subscriptionId(), req.newPriceId(), req.prorationBehavior());
    return ResponseEntity.noContent().build();
  }

  /* ---- Handlers de erro simples (mantêm o JSON no padrão do app) ---- */

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
