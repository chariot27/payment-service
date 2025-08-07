package br.ars.payment_service.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String stripePaymentId;
    private String customerEmail;
    private Long amount;
    private String currency;
    private String status;
    private LocalDateTime createdAt;
}
