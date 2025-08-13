package br.ars.payment_service.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CheckoutRequest(@NotNull UUID userId) {}
