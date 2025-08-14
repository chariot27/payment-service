package br.ars.payment_service.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name="billing_customer", uniqueConstraints = {
  @UniqueConstraint(name="uk_customer_user", columnNames={"user_id"}),
  @UniqueConstraint(name="uk_customer_stripe", columnNames={"stripe_customer_id"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BillingCustomer {
  @Id @GeneratedValue private UUID id;

  @Column(name="user_id", nullable=false) private UUID userId;
  @Column(name="email", nullable=false) private String email;
  @Column(name="stripe_customer_id", nullable=false) private String stripeCustomerId;

  @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
  @Column(name="updated_at", nullable=false) private OffsetDateTime updatedAt;

  @PrePersist void pre() { createdAt = updatedAt = OffsetDateTime.now(); }
  @PreUpdate  void upd()  { updatedAt = OffsetDateTime.now(); }
}
