package br.ars.payment_service.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "assinatura-service", url = "https://subscription-service-r3sg.onrender.com")
public interface AssinaturaClient {

    @GetMapping("/usuario/{userId}")
    Boolean existeAssinatura(@PathVariable("userId") UUID userId);
}
