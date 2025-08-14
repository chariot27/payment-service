package br.ars.payment_service.service;

import br.ars.payment_service.config.StripeProperties;
import br.ars.payment_service.domain.*;
import br.ars.payment_service.dto.*;
import br.ars.payment_service.repo.*;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.param.*;
import com.stripe.net.RequestOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BillingService {

  private final StripeProperties props;
  private final BillingCustomerRepository custRepo;
  private final SubscriptionRecordRepository subRepo;

  @Transactional
  public SubscribeResponse startSubscription(SubscribeRequest in) throws StripeException {
    // 1) Customer (recupera do seu banco se já existir)
    BillingCustomer bc = custRepo.findByUserId(in.userId())
        .orElseGet(() -> createCustomer(in.userId(), in.email()));

    // 2) Subscription default_incomplete + expand PaymentIntent
    String price = (in.priceId() != null && !in.priceId().isBlank())
        ? in.priceId() : props.pricesBasic();

    SubscriptionCreateParams.Builder sb = SubscriptionCreateParams.builder()
        .setCustomer(bc.getStripeCustomerId())
        .addItem(SubscriptionCreateParams.Item.builder().setPrice(price).build())
        .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
        .addAllExpand(List.of("latest_invoice.payment_intent"))
        .setPaymentSettings(SubscriptionCreateParams.PaymentSettings.builder()
            .setSaveDefaultPaymentMethod(
                SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION)
            .build());

    // trial opcional via properties
    
    if (in.metadata() != null && !in.metadata().isEmpty()) {
      sb.putAllMetadata(in.metadata());
    }

    // Idempotência (evita criar 2 vezes se o usuário tocar rápido)
    RequestOptions ro = RequestOptions.builder()
        .setIdempotencyKey(Idem.key("sub", bc.getStripeCustomerId(), price, LocalDate.now().toString()))
        .build();

    Subscription sub = Subscription.create(sb.build(), ro);

    // 3) Ephemeral Key (SDK mobile precisa) — com override da versão da API
    Map<String, Object> ekParams = Map.of("customer", bc.getStripeCustomerId());
    RequestOptions ekOpts = RequestOptions.builder()
        
        .build();
    EphemeralKey ek = EphemeralKey.create(ekParams, ekOpts);

    // 4) Persistência local
    Invoice inv = sub.getLatestInvoiceObject();
    PaymentIntent pi = (inv != null) ? inv.getPaymentIntentObject() : null;

    SubscriptionRecord rec = subRepo.findByStripeSubscriptionId(sub.getId())
        .orElseGet(SubscriptionRecord::new);
    rec.setCustomer(bc);
    rec.setStripeSubscriptionId(sub.getId());
    rec.setStatus(SubscriptionsStatus.valueOf(sub.getStatus().toUpperCase())); // <<< enum correto
    rec.setProductId(sub.getItems().getData().get(0).getPrice().getProduct());
    rec.setPriceId(price);
    rec.setLatestInvoiceId(inv != null ? inv.getId() : null);
    rec.setDefaultPaymentMethod(sub.getDefaultPaymentMethod());
    rec.setCurrentPeriodStart(toODT(sub.getCurrentPeriodStart()));
    rec.setCurrentPeriodEnd(toODT(sub.getCurrentPeriodEnd()));
    rec.setCancelAt(toODT(sub.getCancelAt()));
    rec.setCancelAtPeriodEnd(Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()));
    subRepo.save(rec);

    return new SubscribeResponse(
        props.publishableKey(),
        bc.getStripeCustomerId(),
        sub.getId(),
        (pi != null ? pi.getClientSecret() : null),
        ek.getSecret()
    );
  }

  @Transactional(readOnly = true)
  public SubscriptionStatusResponse getStatus(String subscriptionId) throws StripeException {
    SubscriptionRecord rec = subRepo.findByStripeSubscriptionId(subscriptionId)
        .orElseThrow(() -> new IllegalArgumentException("Subscription não encontrada"));
    return new SubscriptionStatusResponse(
        subscriptionId, rec.getStatus(), rec.getCurrentPeriodEnd(), rec.isCancelAtPeriodEnd()
    );
  }

  @Transactional
  public void applyWebhookUpdate(Subscription sub, Invoice inv) {
    subRepo.findByStripeSubscriptionId(sub.getId()).ifPresent(rec -> {
      rec.setStatus(SubscriptionsStatus.valueOf(sub.getStatus().toUpperCase())); // <<< enum correto
      rec.setLatestInvoiceId(inv != null ? inv.getId() : rec.getLatestInvoiceId());
      rec.setDefaultPaymentMethod(sub.getDefaultPaymentMethod());
      rec.setCurrentPeriodStart(toODT(sub.getCurrentPeriodStart()));
      rec.setCurrentPeriodEnd(toODT(sub.getCurrentPeriodEnd()));
      rec.setCancelAt(toODT(sub.getCancelAt()));
      rec.setCancelAtPeriodEnd(Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()));
      subRepo.save(rec);
    });
  }

    @Transactional
    public void changePlan(String subscriptionId, String newPriceId, String prorationBehavior) throws StripeException {
        Subscription sub = Subscription.retrieve(subscriptionId);

        // mapeia string → enum seguro (create_prorations | none | always_invoice)
        SubscriptionUpdateParams.ProrationBehavior behavior = resolveProration(prorationBehavior);

        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
            .addItem(SubscriptionUpdateParams.Item.builder()
                .setId(sub.getItems().getData().get(0).getId())
                .setPrice(newPriceId)
                .build())
            .setProrationBehavior(behavior)
            .build();

        Subscription updated = sub.update(params);
        applyWebhookUpdate(updated, null);
    }

    /** Normaliza a string enviada e retorna o enum do Stripe. */
    private SubscriptionUpdateParams.ProrationBehavior resolveProration(String p) {
        if (p == null) return SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS;
        switch (p.trim().toLowerCase(Locale.ROOT)) {
            case "none":
            return SubscriptionUpdateParams.ProrationBehavior.NONE;
            case "always_invoice":
            return SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE;
            case "create_prorations":
            default:
            return SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS;
        }
    }

  private BillingCustomer createCustomer(UUID userId, String email) {
    try {
      Customer cust = Customer.create(CustomerCreateParams.builder().setEmail(email).build());
      BillingCustomer bc = BillingCustomer.builder()
          .userId(userId)
          .email(email)
          .stripeCustomerId(cust.getId())
          .build();
      return custRepo.save(bc);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private OffsetDateTime toODT(Long epochSeconds) {
    return (epochSeconds == null)
        ? null
        : OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
  }
}
