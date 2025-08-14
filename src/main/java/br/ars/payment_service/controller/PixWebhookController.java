package br.ars.payment_service.controller;

import br.ars.payment_service.dto.WebhookPixEvent;
import br.ars.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/pix")
@RequiredArgsConstructor
public class PixWebhookController {

    private final PaymentService paymentService;

    /** Endpoint para o PSP postar o evento de PIX (sem HMAC nesta versão). */
    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receive(@RequestBody WebhookPixEvent evt) {
        var opt = paymentService.confirmPayment(evt);
        if (opt.isEmpty()) {
            // 202 para não forçar retry agressivo do PSP quando ainda não casamos o evento
            return ResponseEntity.status(HttpStatus.ACCEPTED).body("payment not found for event");
        }
        return ResponseEntity.ok().build();
    }

    /** TESTE manual (sem PSP): confirma um txid diretamente. */
    @PostMapping("/_test/confirm/{txid}")
    public ResponseEntity<Void> confirmForTest(@PathVariable String txid) {
        paymentService.confirmManual(txid);
        return ResponseEntity.ok().build();
    }
}
