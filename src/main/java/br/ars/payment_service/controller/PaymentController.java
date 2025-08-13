package br.ars.payment_service.controller;

import br.ars.payment_service.domain.Payment;
import br.ars.payment_service.domain.PaymentStatus;
import br.ars.payment_service.domain.SubscriptionStatus;
import br.ars.payment_service.dto.*;
import br.ars.payment_service.repo.PaymentRepository;
import br.ars.payment_service.repo.SubscriptionRepository;
import br.ars.payment_service.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepo;
    private final SubscriptionRepository subRepo;
    private final ObjectMapper om = new ObjectMapper();

    @PostMapping(value="/checkout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CheckoutResponse> checkout(@RequestBody @Valid CheckoutRequest req) {
        Payment p = paymentService.createCheckout(req.userId());
        return ResponseEntity.ok(new CheckoutResponse(
                p.getTxid(), p.getPixPayload(), p.getQrPngBase64(),
                p.getAmount().toPlainString(), p.getExpiresAt()
        ));
    }

    /** Webhook genérico: aponte aqui o PSP/banco para confirmar o PIX. */
    @PostMapping(value="/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> webhook(@RequestBody WebhookPixEvent evt,
                                     @RequestHeader(value="X-Signature", required=false) String sig) {
        // Se quiser autenticar o webhook por shared secret/HMAC, valide "sig" aqui.
        Optional<Payment> opt = paymentService.confirmPayment(evt);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("txid not found");
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status/{txid}")
    public ResponseEntity<StatusResponse> status(@PathVariable String txid) {
        var p = paymentRepo.findByTxid(txid).orElseThrow();
        var sub = subRepo.findByUserId(p.getUserId()).orElse(null);
        String subStatus = sub != null ? sub.getStatus().name() : SubscriptionStatus.INACTIVE.name();
        return ResponseEntity.ok(new StatusResponse(p.getTxid(), p.getStatus().name(), subStatus));
    }
}
