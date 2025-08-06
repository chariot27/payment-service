package br.ars.payment_service.dto;

import br.ars.payment_service.enums.MetodoPagamento;
import br.ars.payment_service.enums.StatusPagamento;

import java.time.LocalDate;
import java.util.UUID;

public class PaymentResponseDTO {

    private UUID id;
    private UUID assinaturaId;
    private MetodoPagamento metodoPagamento;
    private Double valor;
    private LocalDate dataPagamento;
    private LocalDate proximoPagamento;
    private StatusPagamento status;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAssinaturaId() {
        return assinaturaId;
    }

    public void setAssinaturaId(UUID assinaturaId) {
        this.assinaturaId = assinaturaId;
    }

    public MetodoPagamento getMetodoPagamento() {
        return metodoPagamento;
    }

    public void setMetodoPagamento(MetodoPagamento metodoPagamento) {
        this.metodoPagamento = metodoPagamento;
    }

    public Double getValor() {
        return valor;
    }

    public void setValor(Double valor) {
        this.valor = valor;
    }

    public LocalDate getDataPagamento() {
        return dataPagamento;
    }

    public void setDataPagamento(LocalDate dataPagamento) {
        this.dataPagamento = dataPagamento;
    }

    public LocalDate getProximoPagamento() {
        return proximoPagamento;
    }

    public void setProximoPagamento(LocalDate proximoPagamento) {
        this.proximoPagamento = proximoPagamento;
    }

    public StatusPagamento getStatus() {
        return status;
    }

    public void setStatus(StatusPagamento status) {
        this.status = status;
    }
}
