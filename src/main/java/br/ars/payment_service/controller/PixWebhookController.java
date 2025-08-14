// src/main/java/br/ars/payment_service/controller/PixWebhookController.java
package br.ars.payment_service.controller;

import br.ars.payment_service.dto.WebhookPixEvent;
import br.ars.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/pix")
@RequiredArgsConstructor
public class PixWebhookController {

    private final PaymentService paymentService;

    /** PSP envia aqui. Se não achar o Payment, retorna 404 para você saber na hora. */
    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(@RequestBody WebhookPixEvent evt) {
        var opt = paymentService.confirmPayment(evt);
        if (opt.isPresent()) {
            return ResponseEntity.ok().build();
        } else {
            log.warn("[PIX WEBHOOK] 404 - pagamento não encontrado para payload txid={}, e2e={}",
                    evt == null ? null : evt.txid(),
                    evt == null ? null : evt.endToEndId());
            return ResponseEntity.notFound().build();
        }
    }

    /** DEV: confirma um txid manualmente (simula PSP). */
    @PostMapping("/_test/confirm/{txid}")
    public ResponseEntity<Void> confirmForTest(@PathVariable String txid) {
        var evt = new WebhookPixEvent(txid, "E2E-"+txid, "CONFIRMED", java.time.OffsetDateTime.now(), "ASSINATURA");
        var opt = paymentService.confirmPayment(evt);
        return opt.isPresent() ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
