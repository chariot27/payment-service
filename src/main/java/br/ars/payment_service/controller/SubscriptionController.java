package br.ars.payment_service.controller;

import br.ars.payment_service.domain.Subscription;
import br.ars.payment_service.domain.SubscriptionStatus;
import br.ars.payment_service.service.SubscriptionService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subService;

    @GetMapping("/{userId}/status")
    public ResponseEntity<SubscriptionStatusResponse> getStatus(@PathVariable UUID userId) {
        Optional<Subscription> s = subService.getByUser(userId);

        String status = s.map(sub -> sub.getStatus().name())
                .orElse(SubscriptionStatus.INACTIVE.name());

        OffsetDateTime currentPeriodStart = s.map(Subscription::getCurrentPeriodStart).orElse(null);
        OffsetDateTime currentPeriodEnd   = s.map(Subscription::getCurrentPeriodEnd).orElse(null);
        boolean cancelAtPeriodEnd         = s.map(Subscription::isCancelAtPeriodEnd).orElse(false);

        return ResponseEntity.ok(new SubscriptionStatusResponse(
                userId,
                status,
                currentPeriodStart,
                currentPeriodEnd,
                cancelAtPeriodEnd
        ));
    }

    @PostMapping("/{userId}/cancel-at-period-end")
    public ResponseEntity<Void> cancelAtPeriodEnd(@PathVariable UUID userId) {
        subService.cancelAtPeriodEnd(userId);
        return ResponseEntity.ok().build();
    }

    @Data
    @AllArgsConstructor
    public static class SubscriptionStatusResponse {
        private UUID userId;
        private String status; // "ACTIVE" | "INACTIVE" | ...
        private OffsetDateTime currentPeriodStart; // pode ser null
        private OffsetDateTime currentPeriodEnd;   // pode ser null
        private boolean cancelAtPeriodEnd;
    }
}
