package br.ars.payment_service.service;

import br.ars.payment_service.domain.PaymentStatus;
import br.ars.payment_service.repo.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class PaymentHousekeeping {

    private final PaymentRepository paymentRepo;

    /** A cada 5 min: expira pagamentos PENDING vencidos. */
    @Scheduled(cron = "${app.jobs.expire-payments.cron}")
    @Transactional
    public void expirePendings() {
        var now = OffsetDateTime.now();
        paymentRepo.findByStatusAndExpiresAtBefore(PaymentStatus.PENDING, now)
                .forEach(p -> { p.setStatus(PaymentStatus.EXPIRED); paymentRepo.save(p); });
    }
}
