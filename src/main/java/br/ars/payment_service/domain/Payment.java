package br.ars.payment_service.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
  name = "payments",
  indexes = {
    @Index(name="idx_payments_user",    columnList="user_id"),
    @Index(name="idx_payments_status",  columnList="status"),
    @Index(name="idx_payments_expires", columnList="expires_at")
  }
)
@OptimisticLocking(type = OptimisticLockType.VERSION)
@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
public class Payment {

  @Id
  @UuidGenerator
  @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
  private UUID id;

  @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
  private UUID userId;

  @Column(nullable = false, unique = true, length = 35)
  private String txid;

  @Column(name = "end_to_end_id", length = 50)
  private String endToEndId;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PaymentStatus status;

  // ⚠️ TEXT em vez de LOB para evitar LO API/auto-commit
  @Column(name = "pix_payload", nullable = false, columnDefinition = "text")
  private String pixPayload;

  @Column(name = "qr_png_base64", columnDefinition = "text")
  private String qrPngBase64;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "confirmed_at")
  private OffsetDateTime confirmedAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Version
  private long version;

  @PrePersist
  public void onCreate() {
    if (this.createdAt == null) this.createdAt = OffsetDateTime.now();
  }
}

