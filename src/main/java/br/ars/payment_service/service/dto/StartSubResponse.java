package br.ars.payment_service.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StartSubResponse {
    private String publishableKey;
    private String customerId;
    private String subscriptionId;
    private String paymentIntentClientSecret;
    private String ephemeralKeySecret;
}
