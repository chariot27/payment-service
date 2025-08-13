package br.ars.payment_service.service;

import br.ars.payment_service.domain.Subscription;
import br.ars.payment_service.domain.SubscriptionStatus;
import br.ars.payment_service.repo.SubscriptionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subRepo;

    @Value("${app.grace-period-hours}")
    private int graceHours;

    public Optional<Subscription> getByUser(UUID userId) {
        return subRepo.findByUserId(userId);
    }

    @Transactional
    public void cancelAtPeriodEnd(UUID userId) {
        var sub = subRepo.findByUserId(userId).orElseThrow();
        sub.setCancelAtPeriodEnd(true);
        subRepo.save(sub);
    }

    /** A cada 5 min: vira assinatura para PAST_DUE / CANCELED conforme o período. */
    @Scheduled(cron = "${app.jobs.expire-subscriptions.cron}")
    @Transactional
    public void processExpiredSubscriptions() {
        var now = OffsetDateTime.now();
        subRepo.findByStatusAndCurrentPeriodEndBefore(SubscriptionStatus.ACTIVE, now.minusHours(graceHours))
               .forEach(sub -> {
                   if (sub.isCancelAtPeriodEnd()) sub.setStatus(SubscriptionStatus.CANCELED);
                   else sub.setStatus(SubscriptionStatus.PAST_DUE);
                   subRepo.save(sub);
               });
    }
}
