package br.ars.payment_service.clients;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;



@FeignClient(name = "assinatura-service", url = "${services.assinatura.url}")
public interface AssinaturaClient {

    @GetMapping("/assinaturas/existe/{id}")
    Boolean existeAssinatura(@PathVariable("id") UUID id);
}

