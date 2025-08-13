package br.ars.payment_service.controller;

import br.ars.payment_service.domain.Subscription;
import br.ars.payment_service.domain.SubscriptionStatus;
import br.ars.payment_service.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subService;

    @GetMapping("/{userId}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable UUID userId) {
        Optional<Subscription> s = subService.getByUser(userId);
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "status", s.map(x->x.getStatus().name()).orElse(SubscriptionStatus.INACTIVE.name()),
                "currentPeriodStart", s.map(Subscription::getCurrentPeriodStart).orElse(null),
                "currentPeriodEnd",   s.map(Subscription::getCurrentPeriodEnd).orElse(null),
                "cancelAtPeriodEnd",  s.map(Subscription::isCancelAtPeriodEnd).orElse(false)
        ));
    }

    @PostMapping("/{userId}/cancel-at-period-end")
    public ResponseEntity<?> cancelAtPeriodEnd(@PathVariable UUID userId) {
        subService.cancelAtPeriodEnd(userId);
        return ResponseEntity.ok().build();
    }
}
