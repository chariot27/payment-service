package br.ars.payment_service.controller;

import br.ars.payment_service.dto.*;
import br.ars.payment_service.service.BillingService;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {
  private final BillingService billing;

  @PostMapping("/subscribe")
  public ResponseEntity<SubscribeResponse> subscribe(@RequestBody SubscribeRequest req) throws StripeException {
    return ResponseEntity.ok(billing.startSubscription(req));
  }

  @GetMapping("/subscriptions/{id}")
  public ResponseEntity<SubscriptionStatusResponse> getStatus(@PathVariable String id) throws StripeException {
    return ResponseEntity.ok(billing.getStatus(id));
  }

  @PostMapping("/change-plan")
  public ResponseEntity<Void> changePlan(@RequestBody ChangePlanRequest req) throws StripeException {
    billing.changePlan(req.subscriptionId(), req.newPriceId(), req.prorationBehavior());
    return ResponseEntity.ok().build();
  }
}

