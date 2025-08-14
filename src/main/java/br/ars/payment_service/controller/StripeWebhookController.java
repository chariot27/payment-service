package br.ars.payment_service.controller;

import br.ars.payment_service.config.StripeProperties;
import br.ars.payment_service.service.BillingService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

  private final StripeProperties props;
  private final BillingService billing;

  @PostMapping("/webhook")
  public ResponseEntity<String> handle(
      @RequestHeader("Stripe-Signature") String sig,
      @RequestBody String payload) {

    Event event;
    try {
      event = Webhook.constructEvent(
          payload, sig, props.getWebhookSecret());
    } catch (SignatureVerificationException e) {
      log.warn("Webhook signature invalid: {}", e.getMessage());
      return ResponseEntity.status(400).body("invalid sig");
    }

    try {
      switch (event.getType()) {
        case "invoice.paid" -> {
          Invoice inv = (Invoice) event.getDataObjectDeserializer().getObject().orElse(null);
          if (inv != null && inv.getSubscription() != null) {
            Subscription sub = Subscription.retrieve(inv.getSubscription());
            billing.applyWebhookUpdate(sub, inv);
          }
        }
        case "invoice.payment_failed" -> {
          Invoice inv = (Invoice) event.getDataObjectDeserializer().getObject().orElse(null);
          if (inv != null && inv.getSubscription() != null) {
            Subscription sub = Subscription.retrieve(inv.getSubscription());
            billing.applyWebhookUpdate(sub, inv);
          }
        }
        case "customer.subscription.updated",
             "customer.subscription.deleted",
             "customer.subscription.created" -> {
          Subscription sub = (Subscription) event.getDataObjectDeserializer().getObject().orElse(null);
          if (sub != null) billing.applyWebhookUpdate(sub, null);
        }
        default -> log.debug("Unhandled event: {}", event.getType());
      }
    } catch (StripeException e) {
      log.error("Stripe API error", e);
    }

    return ResponseEntity.ok("ok");
  }
}
