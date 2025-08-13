// src/main/java/br/ars/payment_service/controller/PixWebhookController.java
package br.ars.payment_service.controller;

import br.ars.payment_service.dto.WebhookPixEvent;
import br.ars.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pix")
@RequiredArgsConstructor
public class PixWebhookController {

    private final PaymentService paymentService;

    // Versão simples (sem HMAC). O PSP POSTA aqui quando o PIX cai.
    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(@RequestBody WebhookPixEvent evt) {
        paymentService.confirmPayment(evt); // <- isso marca CONFIRMED e ativa a assinatura
        return ResponseEntity.ok().build();
    }

    // ÚTIL PARA TESTE manual (sem PSP): confirma um txid na hora
    @PostMapping("/_test/confirm/{txid}")
    public ResponseEntity<Void> confirmForTest(@PathVariable String txid) {
        var evt = new WebhookPixEvent(txid, "E2E-"+txid, "CONFIRMED", java.time.OffsetDateTime.now(), null);
        paymentService.confirmPayment(evt);
        return ResponseEntity.ok().build();
    }
}
