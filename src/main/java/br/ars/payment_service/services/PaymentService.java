package br.ars.payment_service.services;

import br.ars.payment_service.clients.AssinaturaClient;
import br.ars.payment_service.dto.PaymentRequestDTO;
import br.ars.payment_service.dto.PaymentResponseDTO;
import br.ars.payment_service.enums.StatusPagamento;
import br.ars.payment_service.mappers.PaymentMapper;
import br.ars.payment_service.models.Payment;
import br.ars.payment_service.repositories.PaymentRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final AssinaturaClient assinaturaClient;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentMapper paymentMapper,
                          AssinaturaClient assinaturaClient) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
        this.assinaturaClient = assinaturaClient;
    }

    @Transactional
    public PaymentResponseDTO criarPagamento(PaymentRequestDTO dto) {
        Boolean existe = assinaturaClient.existeAssinatura(dto.getAssinaturaId());
        if (existe == null || !existe) {
            throw new IllegalArgumentException("Assinatura não encontrada.");
        }

        Payment pagamento = paymentMapper.toEntity(dto);
        pagamento.setStatus(StatusPagamento.PAGO);
        pagamento.setDataPagamento(LocalDate.now());

        return paymentMapper.toDto(paymentRepository.save(pagamento));
    }

    public List<PaymentResponseDTO> listarPagamentos() {
        return paymentRepository.findAll().stream()
                .map(paymentMapper::toDto)
                .collect(Collectors.toList());
    }

    public PaymentResponseDTO buscarPorId(UUID id) {
        Payment pagamento = paymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pagamento não encontrado."));
        return paymentMapper.toDto(pagamento);
    }
}
