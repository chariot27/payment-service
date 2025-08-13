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
import java.time.format.DateTimeFormatter;
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

    /** Gera TXID (<= 35 chars): PREFIX-yyyymmddHHmm-xxxxx */
    private String newTxid() {
        String rand = Long.toString(ThreadLocalRandom.current().nextLong(36_000_000L), 36).toUpperCase();
        String when = DateTimeFormatter.ofPattern("yyyyMMddHHmm").format(OffsetDateTime.now());
        String base = (txidPrefix + "-" + when + "-" + rand).replaceAll("[^A-Z0-9\\-]", "");
        return base.length() > 35 ? base.substring(0, 35) : base;
    }

    /** Cria pagamento PENDING e devolve payload/QR estático (TXID "***" dentro do EMV). */
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

        return paymentRepo.save(p); // id é UUID (definido na entidade)
    }

    /** Confirmação vinda do PSP (webhook). Idempotente por TXID. */
    @Transactional
    public Optional<Payment> confirmPayment(WebhookPixEvent evt) {
        if (evt == null || evt.txid() == null || evt.txid().isBlank()) return Optional.empty();

        var opt = paymentRepo.findByTxid(evt.txid());
        if (opt.isEmpty()) return Optional.empty();

        Payment p = opt.get();
        if (p.getStatus() == PaymentStatus.CONFIRMED) {
            // Já confirmado: garante assinatura ativa e retorna
            activateOrExtendSubscription(p.getUserId());
            return Optional.of(p);
        }

        String st = evt.status() == null ? "" : evt.status().trim().toUpperCase();
        switch (st) {
            case "CONFIRMED" -> {
                p.setStatus(PaymentStatus.CONFIRMED);
                p.setConfirmedAt(evt.occurredAt() != null ? evt.occurredAt() : OffsetDateTime.now());
                if (evt.endToEndId() != null && !evt.endToEndId().isBlank()) {
                    p.setEndToEndId(evt.endToEndId());
                }
                paymentRepo.save(p);
                activateOrExtendSubscription(p.getUserId());
            }
            case "FAILED" -> {
                // Só marca FAILED se ainda não estava confirmado
                if (p.getStatus() != PaymentStatus.CONFIRMED) {
                    p.setStatus(PaymentStatus.FAILED);
                    paymentRepo.save(p);
                }
            }
            default -> {
                // estados pendentes/desconhecidos: não altera (idempotência)
            }
        }
        return Optional.of(p);
    }

    /** Confirmação manual para testes: usa o mesmo fluxo da confirmação do PSP. */
    @Transactional
    public void confirmManual(String txid) {
        var opt = paymentRepo.findByTxid(txid);
        if (opt.isEmpty()) return;

        Payment p = opt.get();
        if (p.getStatus() == PaymentStatus.CONFIRMED) {
            activateOrExtendSubscription(p.getUserId());
            return; // idempotente
        }

        p.setStatus(PaymentStatus.CONFIRMED);
        p.setConfirmedAt(OffsetDateTime.now());
        if (p.getEndToEndId() == null || p.getEndToEndId().isBlank()) {
            p.setEndToEndId("MANUAL-" + txid);
        }
        paymentRepo.save(p);

        activateOrExtendSubscription(p.getUserId());
    }

    /** Cria/ativa assinatura por +1 mês a partir de agora, limpando cancelAtPeriodEnd. */
    private void activateOrExtendSubscription(UUID userId) {
        Subscription sub = subRepo.findByUserId(userId).orElseGet(() ->
                Subscription.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .status(SubscriptionStatus.INACTIVE)
                        .cancelAtPeriodEnd(false)
                        .updatedAt(OffsetDateTime.now())
                        .build()
        );

        // Política simples: renova a partir de agora por +1 mês.
        // (Se quiser, pode checar se currentPeriodEnd é futura e estender a partir dela.)
        OffsetDateTime start = OffsetDateTime.now();
        OffsetDateTime end   = start.plusMonths(1);

        sub.setCurrentPeriodStart(start);
        sub.setCurrentPeriodEnd(end);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCancelAtPeriodEnd(false); // remove eventual cancelamento agendado
        sub.setUpdatedAt(OffsetDateTime.now());

        subRepo.save(sub);
    }
}
