package br.ars.payment_service.service;

import br.ars.payment_service.domain.*;
import br.ars.payment_service.dto.WebhookPixEvent;
import br.ars.payment_service.repo.PaymentRepository;
import br.ars.payment_service.repo.SubscriptionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final SubscriptionRepository subRepo;
    private final PixService pixService;

    @Value("${pix.txid.prefix}") private String txidPrefix;
    @Value("${pix.qr.expiration-minutes}") private int expirationMinutes;
    @Value("${pix.amount}") private String amountStr;

    /** Gera TXID (máx 35 chars): PREFIX-yyyymmddHHMM-xxxxx */
    private String newTxid() {
        String rand = Long.toString(ThreadLocalRandom.current().nextLong(36_000_000L), 36).toUpperCase();
        String when = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
                .format(OffsetDateTime.now());
        String base = (txidPrefix + "-" + when + "-" + rand).replaceAll("[^A-Z0-9\\-]", "");
        return base.length() > 35 ? base.substring(0, 35) : base;
    }

    @Transactional
    public Payment createCheckout(UUID userId) {
        String txid = newTxid();
        var pix = pixService.build(txid);

        Payment p = Payment.builder()
                .userId(userId)
                .txid(txid)
                .endToEndId(null)
                .amount(new BigDecimal(amountStr))
                .status(PaymentStatus.PENDING)
                .pixPayload(pix.copiaECola())
                .qrPngBase64(pix.qrBase64())
                .expiresAt(OffsetDateTime.now().plusMinutes(expirationMinutes).truncatedTo(ChronoUnit.SECONDS))
                .build();

        return paymentRepo.save(p); // agora o Postgres gera o BIGINT id
    }

    /** Confirmação por webhook do PSP. Idempotente via txid. */
    @Transactional
    public Optional<Payment> confirmPayment(WebhookPixEvent evt) {
        if (evt == null || evt.txid() == null || evt.txid().isBlank()) return Optional.empty();

        var opt = paymentRepo.findByTxid(evt.txid());
        if (opt.isEmpty()) return Optional.empty();

        Payment p = opt.get();
        if (p.getStatus() == PaymentStatus.CONFIRMED) return Optional.of(p);

        String st = evt.status() == null ? "" : evt.status().trim().toUpperCase();

        switch (st) {
            case "CONFIRMED" -> {
                p.setStatus(PaymentStatus.CONFIRMED);
                p.setConfirmedAt(evt.occurredAt() != null ? evt.occurredAt() : OffsetDateTime.now());
                p.setEndToEndId(evt.endToEndId());
                paymentRepo.save(p);

                // Ativar/criar assinatura
                var sub = subRepo.findByUserId(p.getUserId()).orElseGet(() ->
                        Subscription.builder()
                                .id(UUID.randomUUID())
                                .userId(p.getUserId())
                                .status(SubscriptionStatus.INACTIVE)
                                .cancelAtPeriodEnd(false)
                                .updatedAt(OffsetDateTime.now())
                                .build()
                );
                OffsetDateTime start = OffsetDateTime.now();
                OffsetDateTime end = start.plusMonths(1);
                sub.setCurrentPeriodStart(start);
                sub.setCurrentPeriodEnd(end);
                sub.setStatus(SubscriptionStatus.ACTIVE);
                sub.setCancelAtPeriodEnd(false);
                sub.setUpdatedAt(OffsetDateTime.now());
                subRepo.save(sub);
            }
            case "FAILED" -> {
                p.setStatus(PaymentStatus.FAILED);
                paymentRepo.save(p);
            }
            default -> {
                // ignore estados desconhecidos para manter idempotência
            }
        }
        return Optional.of(p);
    }
}
