package br.ars.payment_service.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
@Entity @Table(name="payments",
        indexes = {
            @Index(name="idx_payments_user", columnList="userId"),
            @Index(name="idx_payments_status", columnList="status"),
            @Index(name="idx_payments_expires", columnList="expiresAt")
        })
@OptimisticLocking(type = OptimisticLockType.VERSION)
public class Payment {
    @Id @UuidGenerator
    private UUID id;

    @Column(nullable=false)
    private UUID userId;

    @Column(nullable=false, unique=true, length=35)
    private String txid;

    @Column(length=50)
    private String endToEndId;

    @Column(nullable=false, precision=12, scale=2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=20)
    private PaymentStatus status;

    @Lob @Column(nullable=false)
    private String pixPayload;    // "copia e cola"

    @Lob
    private String qrPngBase64;   // QR em base64

    @Column(nullable=false)
    private OffsetDateTime createdAt;

    private OffsetDateTime confirmedAt;

    @Column(nullable=false)
    private OffsetDateTime expiresAt;

    @Version
    private long version;

    @PrePersist
    public void onCreate() { this.createdAt = OffsetDateTime.now(); }
}
