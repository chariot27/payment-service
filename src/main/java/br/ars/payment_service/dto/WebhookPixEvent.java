package br.ars.payment_service.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WebhookPixEvent(
        String txid,
        String endToEndId,
        BigDecimal amount,
        String status,             // "CONFIRMED", "FAILED"...
        OffsetDateTime occurredAt
) {}
