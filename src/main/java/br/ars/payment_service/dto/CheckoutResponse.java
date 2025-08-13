package br.ars.payment_service.dto;

import java.time.OffsetDateTime;

public record CheckoutResponse(
        String txid,
        String copiaECola,
        String qrPngBase64,
        String amount,
        OffsetDateTime expiresAt
) {}
