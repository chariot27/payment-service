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

    /** Cria pagamento PENDING e devolve payload/QR estático (TXID "***" no EMV). */
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

        return paymentRepo.save(p);
    }

    /**
     * ✅ Confirmação vinda do PSP (webhook).
     * Localiza por:
     *  1) txid (se não for "***")
     *  2) endToEndId
     *  3) PENDING mais recente de 49.90 contendo "ASSINATURA" no payload
     * Idempotente.
     */
    @Transactional
    public Optional<Payment> confirmPayment(WebhookPixEvent evt) {
        if (evt == null) return Optional.empty();

        String evtTxid = safeStr(evt.getTxid());
        String evtE2e  = safeStr(evt.getEndToEndId());
        String evtSt   = safeStr(evt.getStatus());
        OffsetDateTime evtWhen = evt.getOccurredAt();

        log.info("[PIX WEBHOOK] payload: txid={}, e2e={}, status={}, occurredAt={}",
                evtTxid, evtE2e, evtSt, evtWhen);

        Payment p = null;

        // (1) por txid (ignora "***")
        if (!evtTxid.isBlank() && !"***".equals(evtTxid)) {
            p = paymentRepo.findByTxid(evtTxid).orElse(null);
        }

        // (2) por endToEndId
        if (p == null && !evtE2e.isBlank()) {
            p = paymentRepo.findAll().stream()
                    .filter(x -> evtE2e.equals(safeStr(x.getEndToEndId())))
                    .findFirst()
                    .orElse(null);
        }

        // (3) fallback por valor + descrição "ASSINATURA" + PENDING válido
        if (p == null) {
            BigDecimal expected = new BigDecimal(amountStr);
            OffsetDateTime now = OffsetDateTime.now();
            p = paymentRepo.findAll().stream()
                    .filter(x -> x.getStatus() == PaymentStatus.PENDING)
                    .filter(x -> x.getAmount() != null && expected.compareTo(x.getAmount()) == 0)
                    .filter(x -> hasAssinatura(x.getPixPayload()))
                    .filter(x -> x.getExpiresAt() == null || x.getExpiresAt().isAfter(now))
                    .max(Comparator.comparing(Payment::getExpiresAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
        }

        if (p == null) {
            log.warn("[PIX WEBHOOK] pagamento não localizado (txid={}, e2e={})", evtTxid, evtE2e);
            return Optional.empty();
        }

        // Idempotência: já confirmado
        if (p.getStatus() == PaymentStatus.CONFIRMED) {
            activateOrExtendSubscription(p.getUserId());
            return Optional.of(p);
        }

        String st = evtSt.toUpperCase();
        switch (st) {
            case "CONFIRMED" -> {
                p.setStatus(PaymentStatus.CONFIRMED);
                p.setConfirmedAt(evtWhen != null ? evtWhen : OffsetDateTime.now());
                if (!evtE2e.isBlank()) p.setEndToEndId(evtE2e);
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
            default -> log.info("[PIX WEBHOOK] status '{}' não aplicável. Sem alterações. txid={}", st, p.getTxid());
        }

        return Optional.of(p);
    }

    /** Confirmação manual para testes (DEV). */
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
        if (safeStr(p.getEndToEndId()).isBlank()) {
            p.setEndToEndId("MANUAL-" + txid);
        }
        paymentRepo.save(p);

        activateOrExtendSubscription(p.getUserId());
    }

    /** ✅ Verifica no PSP e confirma se já foi pago; se não, não confirma. */
    @Transactional
    public VerifyResponse verifyAndMaybeConfirm(String txid) {
        Payment p = paymentRepo.findByTxid(txid).orElseThrow();
        PaymentStatus before = p.getStatus();

        PaymentStatus pspStatus = pixService.checkStatus(txid); // PENDING | CONFIRMED | FAILED | EXPIRED

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

    /** ♻️ Reconciliação automática periódica. */
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
                .filter(p -> hasAssinatura(p.getPixPayload()))
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now))
                .forEach(p -> {
                    try {
                        var before = p.getStatus();
                        var v = verifyAndMaybeConfirm(p.getTxid());
                        if (!v.changed() && before == PaymentStatus.PENDING) {
                            // continua pendente; se expirar, verifyAndMaybeConfirm marca EXPIRED
                        }
                    } catch (Exception e) {
                        log.warn("[RECONCILE] Falha ao verificar txid {}: {}", p.getTxid(), e.getMessage());
                    }
                });
    }

    /** Ativa/renova assinatura por +1 mês a partir de agora. */
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

    private static String safeStr(String s) { return (s == null) ? "" : s.trim(); }

    private static boolean hasAssinatura(String payload) {
        if (payload == null) return false;
        return payload.toUpperCase().contains("ASSINATURA");
    }
}
