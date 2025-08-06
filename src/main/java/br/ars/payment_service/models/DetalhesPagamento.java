package br.ars.payment_service.models;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "detalhes_pagamento")
public class DetalhesPagamento {

    @Id
    @GeneratedValue
    private UUID id;

    // Dados comuns
    @Column(name = "titular")
    private String nomeTitular;

    // Cartão
    @Column(name = "numero_cartao")
    private String numeroCartao;

    @Column(name = "validade_cartao")
    private String validade;

    @Column(name = "cvv")
    private String cvv;

    // PIX
    @Column(name = "chave_pix")
    private String chavePix;

    // Boleto
    @Column(name = "codigo_boleto")
    private String codigoBoleto;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getNomeTitular() {
        return nomeTitular;
    }

    public void setNomeTitular(String nomeTitular) {
        this.nomeTitular = nomeTitular;
    }

    public String getNumeroCartao() {
        return numeroCartao;
    }

    public void setNumeroCartao(String numeroCartao) {
        this.numeroCartao = numeroCartao;
    }

    public String getValidade() {
        return validade;
    }

    public void setValidade(String validade) {
        this.validade = validade;
    }

    public String getCvv() {
        return cvv;
    }

    public void setCvv(String cvv) {
        this.cvv = cvv;
    }

    public String getChavePix() {
        return chavePix;
    }

    public void setChavePix(String chavePix) {
        this.chavePix = chavePix;
    }

    public String getCodigoBoleto() {
        return codigoBoleto;
    }

    public void setCodigoBoleto(String codigoBoleto) {
        this.codigoBoleto = codigoBoleto;
    }

    
}
