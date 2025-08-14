package br.ars.payment_service.controller;

import br.ars.payment_service.config.StripeProperties;
import br.ars.payment_service.service.BillingService;
import com.stripe.exception.SignatureVerificationException;
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

    // 1) Verifica a assinatura do webhook
    final Event event;
    try {
      event = Webhook.constructEvent(payload, sig, props.getWebhookSecret());
    } catch (SignatureVerificationException e) {
      log.warn("Webhook signature invalid: {}", e.getMessage());
      return ResponseEntity.status(400).body("invalid sig");
    }

    // 2) Processa leve e responde 200
    final String type = event.getType();
    try {
      switch (type) {
        // NESTES eventos já vem um Subscription tipado na payload
        case "customer.subscription.created":
        case "customer.subscription.updated":
        case "customer.subscription.deleted": {
          Subscription sub = (Subscription) event.getDataObjectDeserializer()
              .getObject()
              .orElse(null);
          billing.applyWebhookUpdate(sub, null);
          break;
        }
        // Para invoices, algumas propriedades não têm getters tipados em 29.x.
        // Apenas passamos o Invoice para log/persist local sem tentar obter o subscriptionId via getter.
        case "invoice.paid":
        case "invoice.payment_failed": {
          Invoice inv = (Invoice) event.getDataObjectDeserializer()
              .getObject()
              .orElse(null);
          billing.applyWebhookUpdate(null, inv);
          break;
        }
        default:
          log.debug("Unhandled event: {}", type);
      }
    } catch (Exception e) {
      // Captura geral para não derrubar o webhook
      log.error("Error handling webhook {}: {}", type, e.getMessage(), e);
    }

    return ResponseEntity.ok("ok");
  }
}
