package br.ars.payment_service.controller;

import br.ars.payment_service.dto.*;
import br.ars.payment_service.service.BillingService;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

  private final BillingService billing;

  @Value("${app.stripe.mobile-api-version:2020-08-27}")
  private String mobileApiVersionDefault;

  @PostMapping("/subscribe")
  public ResponseEntity<SubscribeResponse> subscribe(
      @RequestBody SubscribeRequest req,
      @RequestHeader(value = "Stripe-Version", required = false) String stripeVersionHeader
  ) throws StripeException {

    final String resolvedStripeVersion =
        StringUtils.hasText(req.stripeVersion()) ? req.stripeVersion()
            : (StringUtils.hasText(stripeVersionHeader) ? stripeVersionHeader
            : mobileApiVersionDefault);

    log.info("[BILL][REQ] subscribe userId={}, email={}, priceId={}, stripeVersion(body={}, header={}, resolved={})",
        req.userId(), req.email(), req.priceId(), req.stripeVersion(), stripeVersionHeader, resolvedStripeVersion);

    SubscribeRequest effectiveReq = new SubscribeRequest(
        req.userId(),
        req.email(),
        req.priceId(),
        resolvedStripeVersion
    );

    SubscribeResponse resp = billing.startSubscription(effectiveReq);

    log.info("[BILL][RES] subscribe subscriptionId={}, customerId={}, hasPI={}",
        resp.subscriptionId(), resp.customerId(), resp.paymentIntentClientSecret() != null);

    return ResponseEntity.ok(resp);
  }

  @GetMapping("/subscriptions/{id}")
  public ResponseEntity<SubscriptionStatusResponse> getStatus(@PathVariable String id) throws StripeException {
    log.info("[BILL][REQ] statusById subscriptionId={}", id);
    SubscriptionStatusResponse out = billing.getStatus(id);
    log.info("[BILL][RES] statusById subscriptionId={}, status={}, currentPeriodEnd={}",
        out.subscriptionId(), out.status(), out.currentPeriodEnd());
    return ResponseEntity.ok(out);
  }

  @PostMapping("/change-plan")
  public ResponseEntity<Void> changePlan(@RequestBody ChangePlanRequest req) throws StripeException {
    log.info("[BILL][REQ] changePlan subscriptionId={}, newPriceId={}, proration={}",
        req.subscriptionId(), req.newPriceId(), req.prorationBehavior());
    billing.changePlan(req.subscriptionId(), req.newPriceId(), req.prorationBehavior());
    log.info("[BILL][RES] changePlan subscriptionId={} OK", req.subscriptionId());
    return ResponseEntity.ok().build();
  }
}
