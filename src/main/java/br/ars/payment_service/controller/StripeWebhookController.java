package br.ars.payment_service.controller;

import br.ars.payment_service.service.BillingService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint para webhooks da Stripe.
 * Configure no Dashboard: POST /api/stripe/webhook
 */
@RestController
@RequestMapping(path = "/api/stripe", produces = MediaType.TEXT_PLAIN_VALUE)
public class StripeWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

  private final BillingService billingService;

  @Value("${app.stripe.webhook.secret}")
  private String webhookSecret;

  @Value("${app.stripe.secret-key}")
  private String stripeSecretKey;

  public StripeWebhookController(BillingService billingService) {
    this.billingService = billingService;
  }

  @PostMapping("/webhook")
  public ResponseEntity<String> handle(@RequestHeader("Stripe-Signature") String signature,
                                       @RequestBody String payload) {
    Event event;
    try {
      event = Webhook.constructEvent(payload, signature, webhookSecret);
    } catch (SignatureVerificationException e) {
      log.warn("[STRIPE][WEBHOOK] invalid signature: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid signature");
    }

    try {
      final String type = event.getType();
      final EventDataObjectDeserializer des = event.getDataObjectDeserializer();

      switch (type) {
        case "invoice.payment_succeeded":
        case "invoice.payment_failed": {
          // Seu SDK não tem Invoice#getSubscription(). Vamos pegar do JSON cru.
          String subscriptionId = null;
          String invoiceId = null;

          JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
          JsonObject obj = root.getAsJsonObject("data").getAsJsonObject("object");

          // invoice.id
          if (obj.has("id") && obj.get("id").isJsonPrimitive()) {
            invoiceId = obj.get("id").getAsString();
          }

          // invoice.subscription pode vir como string ou objeto
          if (obj.has("subscription")) {
            JsonElement subEl = obj.get("subscription");
            if (subEl.isJsonPrimitive()) {
              subscriptionId = subEl.getAsString();
            } else if (subEl.isJsonObject() && subEl.getAsJsonObject().has("id")) {
              subscriptionId = subEl.getAsJsonObject().get("id").getAsString();
            }
          }

          // Opcional: recuperar via API para repassar ao service (precisa setar a API key)
          Stripe.apiKey = stripeSecretKey;

          Subscription sub = null;
          Invoice inv = null;
          try {
            if (subscriptionId != null && !subscriptionId.isBlank()) {
              sub = Subscription.retrieve(subscriptionId);
            }
          } catch (StripeException e) {
            log.warn("[WEBHOOK] retrieve subscription err: {}", e.getMessage());
          }
          try {
            if (invoiceId != null && !invoiceId.isBlank()) {
              inv = Invoice.retrieve(invoiceId);
            }
          } catch (StripeException e) {
            log.warn("[WEBHOOK] retrieve invoice err: {}", e.getMessage());
          }

          billingService.applyWebhookUpdate(sub, inv);
          break;
        }

        case "customer.subscription.updated":
        case "customer.subscription.deleted": {
          // Aqui o deserializador costuma funcionar bem
          Object obj = des.getObject().orElse(null);
          Subscription sub = (obj instanceof Subscription) ? (Subscription) obj : null;
          billingService.applyWebhookUpdate(sub, null);
          break;
        }

        default:
          // outros eventos podem ser ignorados por enquanto
          break;
      }
    } catch (Exception e) {
      log.error("[STRIPE][WEBHOOK][ERR] {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error");
    }

    return ResponseEntity.ok("ok");
  }
}
