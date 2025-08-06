package br.ars.payment_service.dto;

import java.util.UUID;

import br.ars.payment_service.enums.MetodoPagamento;

public class PaymentRequestDTO {

    private UUID assinaturaId; // é a mesma do usuario
    private Double valor;
    private MetodoPagamento metodoPagamento;
    private DetalhesPagamentoDTO detalhes;

    public UUID getAssinaturaId() {
        return assinaturaId;
    }

    public void setAssinaturaId(UUID assinaturaId) {
        this.assinaturaId = assinaturaId;
    }

    public Double getValor() {
        return valor;
    }

    public void setValor(Double valor) {
        this.valor = valor;
    }

    public MetodoPagamento getMetodoPagamento() {
        return metodoPagamento;
    }

    public void setMetodoPagamento(MetodoPagamento metodoPagamento) {
        this.metodoPagamento = metodoPagamento;
    }

    public DetalhesPagamentoDTO getDetalhes() {
        return detalhes;
    }

    public void setDetalhes(DetalhesPagamentoDTO detalhes) {
        this.detalhes = detalhes;
    }
}

