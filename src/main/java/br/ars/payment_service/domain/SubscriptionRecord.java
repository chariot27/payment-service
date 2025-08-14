package br.ars.payment_service.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name="subscription_record", uniqueConstraints = {
  @UniqueConstraint(name="uk_sub_stripe", columnNames={"stripe_subscription_id"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubscriptionRecord {
  @Id @GeneratedValue private UUID id;

  @ManyToOne(optional=false, fetch=FetchType.LAZY)
  @JoinColumn(name="billing_customer_id") private BillingCustomer customer;

  @Column(name="stripe_subscription_id", nullable=false) private String stripeSubscriptionId;
  @Enumerated(EnumType.STRING) @Column(nullable=false) private SubscriptionsStatus status;

  @Column(name="product_id") private String productId;
  @Column(name="price_id") private String priceId;
  @Column(name="latest_invoice_id") private String latestInvoiceId;
  @Column(name="default_payment_method") private String defaultPaymentMethod;

  @Column(name="current_period_start") private OffsetDateTime currentPeriodStart;
  @Column(name="current_period_end") private OffsetDateTime currentPeriodEnd;
  @Column(name="cancel_at") private OffsetDateTime cancelAt;
  @Column(name="cancel_at_period_end") private boolean cancelAtPeriodEnd;

  @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
  @Column(name="updated_at", nullable=false) private OffsetDateTime updatedAt;
  @Version private Long version;

  @PrePersist void pre() { createdAt = updatedAt = OffsetDateTime.now(); }
  @PreUpdate  void upd()  { updatedAt = OffsetDateTime.now(); }
}
