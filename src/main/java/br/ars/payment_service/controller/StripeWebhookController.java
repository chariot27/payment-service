package br.ars.payment_service.controller;

import br.ars.payment_service.service.BillingService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
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

@Value("${app.stripe.webhook-secret:${app.stripe.webhook.secret:}}")
private String webhookSecret;

// (opcional) também com default para não quebrar se faltar
@Value("${app.stripe.secret-key:}")
private String stripeSecretKey;

  public StripeWebhookController(BillingService billingService) {
    this.billingService = billingService;
  }

  @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> handle(
      @RequestHeader(name = "Stripe-Signature", required = false) String signature,
      @RequestBody String payload
  ) {
    // Se o secret não estiver configurado, não derruba a aplicação no deploy;
    // apenas rejeita a chamada e loga.
    if (webhookSecret == null || webhookSecret.isBlank()) {
      log.error("[STRIPE][WEBHOOK] webhook secret ausente (app.stripe.webhook-secret). Configure a env STRIPE_WEBHOOK_SECRET.");
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("webhook secret not configured");
    }
    if (signature == null || signature.isBlank()) {
      log.warn("[STRIPE][WEBHOOK] Stripe-Signature ausente");
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("missing signature");
    }

    final Event event;
    try {
      event = Webhook.constructEvent(payload, signature, webhookSecret);
    } catch (SignatureVerificationException e) {
      log.warn("[STRIPE][WEBHOOK] assinatura inválida: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid signature");
    }

    try {
      final String type = event.getType();
      final EventDataObjectDeserializer des = event.getDataObjectDeserializer();

      switch (type) {
        case "invoice.payment_succeeded":
        case "invoice.payment_failed": {
          // SDK 29.x pode não expor getSubscription() diretamente em Invoice -> usa JSON cru
          String subscriptionId = null;
          String invoiceId = null;

          try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            JsonObject obj = root.getAsJsonObject("data").getAsJsonObject("object");

            // invoice.id
            if (obj.has("id") && obj.get("id").isJsonPrimitive()) {
              invoiceId = obj.get("id").getAsString();
            }

            // invoice.subscription pode vir como string OU objeto
            if (obj.has("subscription")) {
              JsonElement subEl = obj.get("subscription");
              if (subEl.isJsonPrimitive()) {
                subscriptionId = subEl.getAsString();
              } else if (subEl.isJsonObject() && subEl.getAsJsonObject().has("id")) {
                subscriptionId = subEl.getAsJsonObject().get("id").getAsString();
              }
            }
          } catch (Throwable t) {
            log.warn("[STRIPE][WEBHOOK] falha ao parsear payload JSON: {}", t.getMessage());
          }

          // Opcional: recuperar via API para enriquecer o update
          Subscription sub = null;
          Invoice inv = null;

          if (stripeSecretKey != null && !stripeSecretKey.isBlank()) {
            Stripe.apiKey = stripeSecretKey;

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
          } else {
            log.warn("[STRIPE][WEBHOOK] app.stripe.secret-key não configurada; seguindo sem retrieve.");
          }

          billingService.applyWebhookUpdate(sub, inv);
          break;
        }

        case "customer.subscription.updated":
        case "customer.subscription.deleted": {
          Object obj = des.getObject().orElse(null);
          Subscription sub = (obj instanceof Subscription) ? (Subscription) obj : null;
          billingService.applyWebhookUpdate(sub, null);
          break;
        }

        default:
          // outros eventos podem ser ignorados por enquanto
          log.debug("[STRIPE][WEBHOOK] evento ignorado: {}", type);
          break;
      }
    } catch (Exception e) {
      log.error("[STRIPE][WEBHOOK][ERR] {}", e.getMessage(), e);
      // 500 faz a Stripe re-tentar; se preferir não re-tentar, devolva 200 "ok"
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error");
    }

    return ResponseEntity.ok("ok");
  }
}
