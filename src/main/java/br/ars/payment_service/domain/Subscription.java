package br.ars.payment_service.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OptimisticLockType;
import org.hibernate.annotations.OptimisticLocking;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
@Entity @Table(name = "subscriptions",
        indexes = {
            @Index(name="idx_subscriptions_status", columnList="status"),
            @Index(name="idx_subscriptions_period_end", columnList="currentPeriodEnd")
        })
@OptimisticLocking(type = OptimisticLockType.VERSION)
public class Subscription {
    @Id @UuidGenerator
    private UUID id;

    @Column(nullable=false, unique=true)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=20)
    private SubscriptionStatus status;

    private OffsetDateTime currentPeriodStart;
    private OffsetDateTime currentPeriodEnd;

    @Column(nullable=false)
    private boolean cancelAtPeriodEnd;

    @Column(nullable=false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    @PrePersist @PreUpdate
    public void touch() { this.updatedAt = OffsetDateTime.now(); }
}
