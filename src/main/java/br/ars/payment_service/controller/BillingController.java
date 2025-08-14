package br.ars.payment_service.controller;

import br.ars.payment_service.dto.ChangePlanRequest;
import br.ars.payment_service.dto.SubscribeRequest;
import br.ars.payment_service.dto.SubscribeResponse;
import br.ars.payment_service.dto.SubscriptionStatusResponse;
import br.ars.payment_service.service.BillingService;
import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
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
      @RequestBody @Valid SubscribeRequest req,
      @RequestHeader(value = "Stripe-Version", required = false) String stripeVersionHeader1,
      @RequestHeader(value = "stripe-version", required = false) String stripeVersionHeader2
  ) throws StripeException {

    // aceita ambas capitalizações do header
    final String headerVersion = StringUtils.hasText(stripeVersionHeader1) ? stripeVersionHeader1 : stripeVersionHeader2;

    // prioridade: body -> header -> default (e valida formato)
    final String resolvedStripeVersion = chooseStripeVersion(
        safeTrim(req.stripeVersion()),
        safeTrim(headerVersion),
        safeTrim(mobileApiVersionDefault)
    );

    log.info("[BILL][REQ] subscribe userId={}, email={}, priceId={}, stripeVersion(body={}, header={}, resolved={})",
        req.userId(), req.email(), req.priceId(), req.stripeVersion(), headerVersion, resolvedStripeVersion);

    final SubscribeRequest effectiveReq = new SubscribeRequest(
        req.userId(),
        req.email(),
        req.priceId(),
        resolvedStripeVersion
    );

    final SubscribeResponse resp = billing.startSubscription(effectiveReq);

    log.info("[BILL][RES] subscribe subscriptionId={}, customerId={}, hasPI={}",
        resp.subscriptionId(), resp.customerId(), resp.paymentIntentClientSecret() != null);

    return ResponseEntity.ok(resp);
  }

  @GetMapping("/subscriptions/{id}")
  public ResponseEntity<SubscriptionStatusResponse> getStatus(@PathVariable String id) throws StripeException {
    log.info("[BILL][REQ] statusById subscriptionId={}", id);
    final SubscriptionStatusResponse out = billing.getStatus(id);
    log.info("[BILL][RES] statusById subscriptionId={}, status={}, currentPeriodEnd={}",
        out.subscriptionId(), out.status(), out.currentPeriodEnd());
    return ResponseEntity.ok(out);
  }

  @PostMapping("/change-plan")
  public ResponseEntity<Void> changePlan(@RequestBody @Valid ChangePlanRequest req) throws StripeException {
    log.info("[BILL][REQ] changePlan subscriptionId={}, newPriceId={}, proration={}",
        req.subscriptionId(), req.newPriceId(), req.prorationBehavior());
    billing.changePlan(req.subscriptionId(), req.newPriceId(), req.prorationBehavior());
    log.info("[BILL][RES] changePlan subscriptionId={} OK", req.subscriptionId());
    return ResponseEntity.ok().build();
  }

  // ---------- helpers ----------

  private static String safeTrim(String v) {
    return v == null ? null : v.trim();
  }

  /**
   * Retorna a primeira versão válida (YYYY-MM-DD) dentre as candidatas; se nenhuma for válida, usa o default.
   */
  private String chooseStripeVersion(String bodyCandidate, String headerCandidate, String defaultCandidate) {
    String v = firstNonEmpty(bodyCandidate, headerCandidate, defaultCandidate);
    if (StringUtils.hasText(v) && v.matches("\\d{4}-\\d{2}-\\d{2}")) {
      return v;
    }
    // fallback: se o default estiver mal formatado por engano, usa a constante de classe
    return mobileApiVersionDefault;
  }

  private static String firstNonEmpty(String... values) {
    if (values == null) return null;
    for (String s : values) {
      if (StringUtils.hasText(s)) return s;
    }
    return null;
  }
}
