package br.ars.payment_service.dto;


import java.util.UUID;

import lombok.Data;

@Data
public class CreatePaymentRequestDTO {
    private UUID userId;
    private String email;
    private String paymentMethodId;
}
