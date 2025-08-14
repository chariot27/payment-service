// src/main/java/br/ars/payment_service/service/PaymentService.java
package br.ars.payment_service.service;

import br.ars.payment_service.domain.*;
import br.ars.payment_service.dto.VerifyResponse;
import br.ars.payment_service.dto.WebhookPixEvent;
import br.ars.payment_service.repo.PaymentRepository;
import br.ars.payment_service.repo.SubscriptionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final SubscriptionRepository subRepo;
    private final PixService pixService;

    @Value("${pix.txid.prefix}") private String txidPrefix;
    @Value("${pix.qr.expiration-minutes}") private int expirationMinutes;
    @Value("${pix.amount}") private String amountStr; // "49.90"

    /** Gera TXID (<= 35 chars): PREFIX-yyyymmddHHmm-xxxxx */
    private String newTxid() {
        String rand = Long.toString(ThreadLocalRandom.current().nextLong(36_000_000L), 36).toUpperCase();
        String when = DateTimeFormatter.ofPattern("yyyyMMddHHmm").format(OffsetDateTime.now());
        String base = (txidPrefix + "-" + when + "-" + rand).replaceAll("[^A-Z0-9\\-]", "");
        return base.length() > 35 ? base.substring(0, 35) : base;
    }

    /** Cria pagamento PENDING e devolve payload/QR (se usar QR estático, TXID no EMV será "***"). */
    @Transactional
    public Payment createCheckout(UUID userId) {
        String txid = newTxid();
        var pix = pixService.build(txid); // se usar QR dinâmico no PSP, esse método deve chamar a API do PSP

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

        return paymentRepo.save(p);
    }

    /**
     * ✅ Confirmação vinda do PSP (webhook).
     * Tenta localizar por:
     *  1) txid (se não for "***")
     *  2) endToEndId
     *  3) PENDING mais recente do valor configurado (49.90) ainda válido
     * Idempotente e robusto para QR estático.
     */
    @Transactional
    public Optional<Payment> confirmPayment(WebhookPixEvent evt) {
        if (evt == null) return Optional.empty();

        log.info("[PIX WEBHOOK] payload: txid={}, e2e={}, status={}, occurredAt={}, desc={}",
                safe(evt.txid()), safe(evt.endToEndId()), safe(evt.status()), evt.occurredAt(), safe(evt.description()));

        Payment p = null;

        // 1) tenta por txid (ignora "***")
        String tx = safe(evt.txid());
        if (!tx.isBlank() && !"***".equals(tx)) {
            p = paymentRepo.findByTxid(tx).orElse(null);
        }

        // 2) tenta por endToEndId (se houver)
        if (p == null) {
            String e2e = safe(evt.endToEndId());
            if (!e2e.isBlank()) {
                p = paymentRepo.findAll().stream()
                        .filter(x -> e2e.equals(safe(x.getEndToEndId())))
                        .findFirst().orElse(null);
            }
        }

        // 3) fallback por valor + status PENDING + não expirado
        if (p == null) {
            BigDecimal expected = new BigDecimal(amountStr);
            OffsetDateTime now = OffsetDateTime.now();
            p = paymentRepo.findAll().stream()
                    .filter(x -> x.getStatus() == PaymentStatus.PENDING)
                    .filter(x -> x.getAmount() != null && expected.compareTo(x.getAmount()) == 0)
                    .filter(x -> x.getExpiresAt() == null || x.getExpiresAt().isAfter(now))
                    .max(Comparator.comparing(Payment::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
        }

        if (p == null) {
            log.warn("[PIX WEBHOOK] pagamento NÃO localizado (txid={}, e2e={})", tx, safe(evt.endToEndId()));
            return Optional.empty();
        }

        // Idempotência
        if (p.getStatus() == PaymentStatus.CONFIRMED) {
            activateOrExtendSubscription(p.getUserId());
            log.info("[PIX WEBHOOK] já CONFIRMADO (txid={}), assinatura garantida", p.getTxid());
            return Optional.of(p);
        }

        // Mapeia status do PSP para o nosso enum
        String st = safe(evt.status()).toUpperCase();
        if (st.equals("PAID")) st = "CONFIRMED"; // alguns PSPs usam "PAID"

        switch (st) {
            case "CONFIRMED" -> {
                p.setStatus(PaymentStatus.CONFIRMED);
                p.setConfirmedAt(evt.occurredAt() != null ? evt.occurredAt() : OffsetDateTime.now());
                String e2e = safe(evt.endToEndId());
                if (!e2e.isBlank()) p.setEndToEndId(e2e);
                paymentRepo.save(p);
                activateOrExtendSubscription(p.getUserId());
                log.info("[PIX WEBHOOK] CONFIRMED txid={}, e2e={}", p.getTxid(), p.getEndToEndId());
            }
            case "FAILED" -> {
                if (p.getStatus() != PaymentStatus.CONFIRMED) {
                    p.setStatus(PaymentStatus.FAILED);
                    paymentRepo.save(p);
                    log.info("[PIX WEBHOOK] FAILED txid={}", p.getTxid());
                }
            }
            case "EXPIRED" -> {
                if (p.getStatus() == PaymentStatus.PENDING) {
                    p.setStatus(PaymentStatus.EXPIRED);
                    paymentRepo.save(p);
                    log.info("[PIX WEBHOOK] EXPIRED txid={}", p.getTxid());
                }
            }
            default -> {
                log.info("[PIX WEBHOOK] status '{}' não aplicável. Sem alterações. txid={}", st, p.getTxid());
            }
        }

        return Optional.of(p);
    }

    /** DEV: confirma manualmente um txid (idempotente). */
    @Transactional
    public void confirmManual(String txid) {
        var opt = paymentRepo.findByTxid(txid);
        if (opt.isEmpty()) return;

        Payment p = opt.get();
        if (p.getStatus() == PaymentStatus.CONFIRMED) {
            activateOrExtendSubscription(p.getUserId());
            return;
        }

        p.setStatus(PaymentStatus.CONFIRMED);
        p.setConfirmedAt(OffsetDateTime.now());
        if (safe(p.getEndToEndId()).isBlank()) {
            p.setEndToEndId("MANUAL-" + txid);
        }
        paymentRepo.save(p);
        activateOrExtendSubscription(p.getUserId());
    }

    /** ✅ Consulta PSP e (talvez) confirma. */
    @Transactional
    public VerifyResponse verifyAndMaybeConfirm(String txid) {
        Payment p = paymentRepo.findByTxid(txid).orElseThrow();
        PaymentStatus before = p.getStatus();

        PaymentStatus pspStatus = pixService.checkStatus(txid); // implementar no PixService quando usar PSP

        if (pspStatus == PaymentStatus.CONFIRMED && before != PaymentStatus.CONFIRMED) {
            p.setStatus(PaymentStatus.CONFIRMED);
            p.setConfirmedAt(OffsetDateTime.now());
            paymentRepo.save(p);
            activateOrExtendSubscription(p.getUserId());
        } else if ((pspStatus == PaymentStatus.FAILED || pspStatus == PaymentStatus.EXPIRED)
                && before == PaymentStatus.PENDING) {
            p.setStatus(pspStatus);
            paymentRepo.save(p);
        }

        if (p.getStatus() == PaymentStatus.PENDING
                && p.getExpiresAt() != null
                && p.getExpiresAt().isBefore(OffsetDateTime.now())) {
            p.setStatus(PaymentStatus.EXPIRED);
            paymentRepo.save(p);
        }

        var sub = subRepo.findByUserId(p.getUserId()).orElse(null);
        String subStatus = sub != null ? sub.getStatus().name() : SubscriptionStatus.INACTIVE.name();
        boolean changed = p.getStatus() != before;

        return new VerifyResponse(p.getTxid(), p.getStatus().name(), subStatus, changed);
    }

    /** ♻️ Reconciliação automática: verifica PENDING (49.90) e atualiza status. */
    @Scheduled(
            fixedDelayString = "${pix.reconcile-interval-ms:30000}",
            initialDelayString = "${pix.reconcile-initial-delay-ms:10000}"
    )
    public void reconcilePendingAssinaturas() {
        BigDecimal expected = new BigDecimal(amountStr);
        OffsetDateTime now = OffsetDateTime.now();

        paymentRepo.findAll().stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .filter(p -> p.getAmount() != null && expected.compareTo(p.getAmount()) == 0)
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now))
                .forEach(p -> {
                    try {
                        var before = p.getStatus();
                        var v = verifyAndMaybeConfirm(p.getTxid());
                        if (v.changed()) {
                            log.info("[RECONCILE] {} -> {} (txid={})", before, p.getStatus(), p.getTxid());
                        }
                    } catch (Exception e) {
                        log.warn("[RECONCILE] Falha ao verificar txid {}: {}", p.getTxid(), e.getMessage());
                    }
                });
    }

    /** Ativa/estende assinatura por +1 mês a partir de agora. */
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

        OffsetDateTime start = OffsetDateTime.now();
        OffsetDateTime end   = start.plusMonths(1);

        sub.setCurrentPeriodStart(start);
        sub.setCurrentPeriodEnd(end);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCancelAtPeriodEnd(false);
        sub.setUpdatedAt(OffsetDateTime.now());

        subRepo.save(sub);
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
