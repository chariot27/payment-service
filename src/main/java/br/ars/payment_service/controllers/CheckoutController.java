package br.ars.payment_service.controllers;

import br.ars.payment_service.dto.CheckoutRequest;
import br.ars.payment_service.services.StripeService;
import com.stripe.exception.StripeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    @Autowired
    private StripeService stripeService;

    /**
     * Cria uma sessão de checkout do Stripe com base nos dados enviados.
     */
    @PostMapping("/create-checkout-session")
    public ResponseEntity<Map<String, String>> createCheckoutSession(@RequestBody CheckoutRequest request) throws StripeException {
        return ResponseEntity.ok(stripeService.createCheckoutSession(request));
    }

    /**
     * Webhook que o Stripe chama após eventos (como pagamento confirmado).
     * Atenção: Stripe exige validação de assinatura para segurança.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader
    ) {
        log.info("🎯 Webhook recebido do Stripe.");
        return stripeService.handleStripeWebhook(payload, sigHeader);
    }
}
