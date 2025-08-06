package br.ars.payment_service.models;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

import br.ars.payment_service.enums.MetodoPagamento;
import br.ars.payment_service.enums.StatusPagamento;

@Entity
@Table(name = "pagamentos")
public class Payment {

    @Id
    @GeneratedValue
    @Column(name = "id")
    private UUID id;

    @Column(name = "assinatura_id", nullable = false)
    private UUID assinaturaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_pagamento", nullable = false)
    private MetodoPagamento metodoPagamento;

    @Column(name = "valor", nullable = false)
    private Double valor;

    @Column(name = "data_pagamento")
    private LocalDate dataPagamento;

    @Column(name = "proximo_pagamento")
    private LocalDate proximoPagamento;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StatusPagamento status;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "detalhes_id")
    private DetalhesPagamento detalhes;

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

    public DetalhesPagamento getDetalhes() {
        return detalhes;
    }

    public void setDetalhes(DetalhesPagamento detalhes) {
        this.detalhes = detalhes;
    }

}
