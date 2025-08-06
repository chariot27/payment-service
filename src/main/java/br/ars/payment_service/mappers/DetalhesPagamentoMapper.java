package br.ars.payment_service.mappers;

import br.ars.payment_service.dto.DetalhesPagamentoDTO;
import br.ars.payment_service.models.DetalhesPagamento;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface DetalhesPagamentoMapper {

    DetalhesPagamentoMapper INSTANCE = Mappers.getMapper(DetalhesPagamentoMapper.class);

    DetalhesPagamento toEntity(DetalhesPagamentoDTO dto);

    DetalhesPagamentoDTO toDto(DetalhesPagamento entity);
}
