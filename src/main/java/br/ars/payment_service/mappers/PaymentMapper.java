package br.ars.payment_service.mappers;

import br.ars.payment_service.dto.PaymentRequestDTO;
import br.ars.payment_service.dto.PaymentResponseDTO;
import br.ars.payment_service.models.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(source = "assinaturaId", target = "assinaturaId")
    @Mapping(source = "valor", target = "valor")
    @Mapping(source = "metodoPagamento", target = "metodoPagamento")
    @Mapping(source = "detalhes", target = "detalhes")
    Payment toEntity(PaymentRequestDTO dto);

    @Mapping(source = "id", target = "id")
    @Mapping(source = "assinaturaId", target = "assinaturaId")
    @Mapping(source = "metodoPagamento", target = "metodoPagamento")
    @Mapping(source = "valor", target = "valor")
    @Mapping(source = "dataPagamento", target = "dataPagamento")
    @Mapping(source = "proximoPagamento", target = "proximoPagamento")
    @Mapping(source = "status", target = "status")
    PaymentResponseDTO toDto(Payment entity);
}
