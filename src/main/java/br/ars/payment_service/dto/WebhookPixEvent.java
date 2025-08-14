// src/main/java/br/ars/payment_service/dto/WebhookPixEvent.java
package br.ars.payment_service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPixEvent(
        String txid,
        String endToEndId,
        String status,              // "CONFIRMED", "PAID", "FAILED", "EXPIRED"...
        OffsetDateTime occurredAt,
        String description          // opcional (ex.: "ASSINATURA")
) {}
