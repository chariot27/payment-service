// src/main/java/br/ars/payment_service/dto/WebhookPixEvent.java
package br.ars.payment_service.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@ToString
public class WebhookPixEvent {

    /** Para QR dinâmico o PSP manda seu TXID; no estático costuma vir "***" */
    private String txid;

    /** Id end-to-end do pagamento (E2E) vindo do PSP/banco */
    private String endToEndId;

    /** Exemplos: CONFIRMED | PENDING | FAILED | EXPIRED */
    private String status;

    /** Valor pago; alguns PSPs enviam como "valor" (pt-BR) */
    @JsonAlias({"amount", "valor"})
    private BigDecimal amount;

    /** Momento em que o evento ocorreu no PSP */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]XXX")
    private OffsetDateTime occurredAt;

    /** Qualquer carga extra que o PSP enviar */
    private Map<String, Object> extra;
}
