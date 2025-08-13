// br.ars.payment_service.dto.CheckoutResponse.java
package br.ars.payment_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record CheckoutResponse(
    String txid,
    @JsonProperty("copiaECola") String copiaECola,
    String qrPngBase64,
    String amount,
    OffsetDateTime expiresAt
) {}
