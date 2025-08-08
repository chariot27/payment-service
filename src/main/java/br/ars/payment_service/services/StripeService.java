package br.ars.payment_service.services;

import br.ars.payment_service.dto.CheckoutRequest;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class StripeService {

    @Value("${stripe.webhook.secret}")
    private String endpointSecret;

    @Value("${stripe.price.id}")
    private String priceId;

    /**
     * ✅ Cria uma sessão de checkout do Stripe para assinatura.
     */
    public Map<String, String> createCheckoutSession(CheckoutRequest request) throws StripeException {
        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
            .setSuccessUrl("https://seusite.com/sucesso")
            .setCancelUrl("https://seusite.com/cancelado")
            .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
            .setCustomerEmail(request.getCustomerEmail())
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPrice(priceId)
                    .build()
            )
            .build();

        Session session = Session.create(params);

        Map<String, String> response = new HashMap<>();
        response.put("checkoutUrl", session.getUrl());
        return response;
    }

    /**
     * ✅ Trata o webhook enviado pelo Stripe após eventos como pagamento concluído.
     */
    public ResponseEntity<String> handleStripeWebhook(String payload, String sigHeader) {
        try {
            Event event = Webhook.constructEvent(payload, sigHeader, endpointSecret);

            if ("checkout.session.completed".equals(event.getType())) {
                Session session = (Session) event.getDataObjectDeserializer()
                        .getObject()
                        .orElseThrow(() -> new IllegalArgumentException("Sessão não encontrada"));

                String customerEmail = session.getCustomerDetails().getEmail();
                Long amountTotal = session.getAmountTotal();

                // 🔄 Atualizar lógica de backend com os dados do pagamento
                System.out.println("💸 Assinatura confirmada: " + customerEmail + " | R$ " + (amountTotal / 100.0));
            }

            return ResponseEntity.ok("Webhook recebido com sucesso");

        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Webhook inválido: " + e.getMessage());
        }
    }
}
