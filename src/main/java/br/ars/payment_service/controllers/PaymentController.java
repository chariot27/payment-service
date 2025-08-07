package br.ars.payment_service.controllers;

import br.ars.payment_service.dto.CreatePaymentRequestDTO;
import br.ars.payment_service.services.PaymentService;
import com.stripe.exception.StripeException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private final PaymentService stripePaymentService;

    public PaymentController(PaymentService stripePaymentService) {
        this.stripePaymentService = stripePaymentService;
    }

    @PostMapping("/subscribe")
    public ResponseEntity<String> realizarPagamento(@RequestBody CreatePaymentRequestDTO dto) throws StripeException {
        boolean sucesso = stripePaymentService.processarPagamento(dto);
        if (sucesso) {
            return ResponseEntity.ok("Pagamento aprovado e assinatura ativa.");
        } else {
            return ResponseEntity.badRequest().body("Pagamento falhou ou assinatura já existe.");
        }
    }
}
