package br.ars.payment_service.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
@Entity
@Table(
    name = "payments",
    indexes = {
        @Index(name = "idx_payments_user",    columnList = "user_id"),
        @Index(name = "idx_payments_status",  columnList = "status"),
        @Index(name = "idx_payments_expires", columnList = "expires_at")
    }
)
@OptimisticLocking(type = OptimisticLockType.VERSION)
public class Payment {

    // ⚠️ A tabela usa BIGINT. Trocar para Long + IDENTITY elimina o erro do Postgres.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "txid", nullable = false, unique = true, length = 35)
    private String txid;

    @Column(name = "end_to_end_id", length = 50)
    private String endToEndId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Lob
    @Column(name = "pix_payload", nullable = false)
    private String pixPayload;    // código "copia e cola"

    @Lob
    @Column(name = "qr_png_base64")
    private String qrPngBase64;   // QR em base64

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Version
    @Column(name = "version")
    private long version;

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
