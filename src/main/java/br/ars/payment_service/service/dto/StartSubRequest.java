package br.ars.payment_service.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
public class StartSubRequest {
    private String userId;        // UUID em String para simplificar binding
    private String email;         // mesmo e-mail do login
    private String priceId;       // opcional, se não vier usa default do properties
    private String stripeVersion; // opcional, se não vier usa app.stripe.mobile-api-version
}
