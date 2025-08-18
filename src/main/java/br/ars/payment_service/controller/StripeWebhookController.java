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

  // Usado para enriquecer dados (retrieve de Subscription/Invoice) quando necessário
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
    if (webhookSecret == null || webhookSecret.isBlank()) {
      log.error("[STRIPE][WEBHOOK] webhook secret ausente (app.stripe.webhook-secret). Configure STRIPE_WEBHOOK_SECRET.");
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

      log.info("[STRIPE][WEBHOOK] {} | eventId={}", type, event.getId());

      switch (type) {
        // Paga a fatura inicial da assinatura (ou falha). É o gatilho mais confiável pra “virar” ACTIVE/TRIALING.
        case "invoice.payment_succeeded":
        case "invoice.payment_failed": {
          String subscriptionId = null;
          String invoiceId = null;

          // 1) Tente extrair via JSON cru (robusto a mudanças de SDK)
          try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            JsonObject obj = root.getAsJsonObject("data").getAsJsonObject("object");

            if (obj.has("id") && obj.get("id").isJsonPrimitive()) {
              invoiceId = obj.get("id").getAsString();
            }

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

          // 2) Se possível, enriqueça com retrieve (útil para current_period_end, cancelAtPeriodEnd etc.)
          Subscription sub = null;
          Invoice inv = null;

          if (stripeSecretKey != null && !stripeSecretKey.isBlank()) {
            Stripe.apiKey = stripeSecretKey;
            try {
              if (invoiceId != null && !invoiceId.isBlank()) {
                inv = Invoice.retrieve(invoiceId);
              }
            } catch (StripeException e) {
              log.warn("[WEBHOOK] retrieve invoice err: {}", e.getMessage());
            }

            try {
              if (subscriptionId != null && !subscriptionId.isBlank()) {
                // Expand para ter PI da invoice atual e evitar roundtrips se o service precisar
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("expand", java.util.List.of("latest_invoice.payment_intent"));
                sub = Subscription.retrieve(subscriptionId, params, null);
              }
            } catch (StripeException e) {
              log.warn("[WEBHOOK] retrieve subscription err: {}", e.getMessage());
            }
          } else {
            log.warn("[STRIPE][WEBHOOK] app.stripe.secret-key não configurada; seguindo sem retrieve.");
          }

          // 3) Delegue ao service (ele persiste ACTIVE/TRIALING/CANCELED etc.)
          billingService.applyWebhookUpdate(sub, inv);
          break;
        }

        // Mudanças de status da assinatura (created/updated/deleted)
        case "customer.subscription.created":
        case "customer.subscription.updated":
        case "customer.subscription.deleted": {
          Subscription sub = null;

          // Priorize o objeto desserializado pelo SDK (se disponível)
          if (des.getObject().isPresent() && des.getObject().get() instanceof Subscription s) {
            sub = s;
          } else {
            // Fallback minimalista: tente extrair o subscriptionId do payload cru e dar retrieve
            try {
              JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
              JsonObject obj = root.getAsJsonObject("data").getAsJsonObject("object");
              String subscriptionId = obj.has("id") ? obj.get("id").getAsString() : null;

              if (stripeSecretKey != null && subscriptionId != null && !subscriptionId.isBlank()) {
                Stripe.apiKey = stripeSecretKey;
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("expand", java.util.List.of("latest_invoice.payment_intent"));
                sub = Subscription.retrieve(subscriptionId, params, null);
              }
            } catch (Exception ex) {
              log.warn("[WEBHOOK] fallback retrieve sub err: {}", ex.getMessage());
            }
          }

          billingService.applyWebhookUpdate(sub, null);
          break;
        }

        // Opcional: outros eventos (ex.: payment_intent.succeeded) podem ser usados como “redundância”
        default:
          log.debug("[STRIPE][WEBHOOK] evento ignorado: {}", type);
          break;
      }
    } catch (Exception e) {
      log.error("[STRIPE][WEBHOOK][ERR] {}", e.getMessage(), e);
      // 500 força retry pela Stripe (bom quando a persistência falha e você quer nova tentativa)
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error");
    }

    // 200 = recebido com sucesso
    return ResponseEntity.ok("ok");
  }
}
