// src/main/java/br/ars/payment_service/dto/WebhookPixEvent.java
package br.ars.payment_service.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WebhookPixEvent(
        String txid,            // TXID da cobrança
        String endToEndId,      // e2e id do PSP
        String status,          // "CONFIRMED" | "FAILED" | ...
        OffsetDateTime occurredAt,
        BigDecimal amount
) {}
