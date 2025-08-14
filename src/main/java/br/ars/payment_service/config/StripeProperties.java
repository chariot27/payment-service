package br.ars.payment_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.stripe")
public record StripeProperties(
    String publishableKey,
    String secretKey,
    String webhookSecret,
    String apiVersion,
    String pricesBasic,
    
    Integer webhookToleranceSeconds
) {}
