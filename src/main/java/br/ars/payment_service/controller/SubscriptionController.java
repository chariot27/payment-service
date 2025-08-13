package br.ars.payment_service.controller;

import br.ars.payment_service.domain.Subscription;
import br.ars.payment_service.domain.SubscriptionStatus;
import br.ars.payment_service.service.SubscriptionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subService;

    @GetMapping(value = "/{userId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SubscriptionStatusResponse> getStatus(@PathVariable UUID userId) {
        Subscription s = subService.getByUser(userId).orElse(null);

        var dto = new SubscriptionStatusResponse(
                userId,
                s != null ? s.getStatus() : SubscriptionStatus.INACTIVE,
                s != null ? s.getCurrentPeriodStart() : null,
                s != null ? s.getCurrentPeriodEnd()   : null,
                s != null && s.isCancelAtPeriodEnd()
        );
        return ResponseEntity.ok(dto);
    }

    @PostMapping(value = "/{userId}/cancel-at-period-end")
    public ResponseEntity<Void> cancelAtPeriodEnd(@PathVariable UUID userId) {
        subService.cancelAtPeriodEnd(userId); // se não existir, o service decide (404 ou no-op)
        return ResponseEntity.ok().build();
    }

    @JsonInclude(Include.NON_NULL)
    public record SubscriptionStatusResponse(
            UUID userId,
            SubscriptionStatus status,          // serializa como "ACTIVE", "INACTIVE", etc.
            OffsetDateTime currentPeriodStart,  // pode ser null
            OffsetDateTime currentPeriodEnd,    // pode ser null
            boolean cancelAtPeriodEnd
    ) {}
}
