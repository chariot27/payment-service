package br.ars.payment_service.controllers;

import br.ars.payment_service.dto.PaymentRequestDTO;
import br.ars.payment_service.dto.PaymentResponseDTO;
import br.ars.payment_service.services.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pagamentos")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponseDTO> criarPagamento(@RequestBody PaymentRequestDTO dto) {
        PaymentResponseDTO response = paymentService.criarPagamento(dto);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<PaymentResponseDTO>> listarPagamentos() {
        List<PaymentResponseDTO> pagamentos = paymentService.listarPagamentos();
        return ResponseEntity.ok(pagamentos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponseDTO> buscarPorId(@PathVariable UUID id) {
        PaymentResponseDTO pagamento = paymentService.buscarPorId(id);
        return ResponseEntity.ok(pagamento);
    }
}
