package br.ars.payment_service.repositories;

import br.ars.payment_service.models.DetalhesPagamento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DetalhesPagamentoRepository extends JpaRepository<DetalhesPagamento, UUID> {
}
