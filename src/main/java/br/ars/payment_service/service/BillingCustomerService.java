package br.ars.payment_service.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.CustomerSearchResult;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerSearchParams;
import com.stripe.param.CustomerUpdateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Customer único por userId:
 * - Busca por metadata['userId'] antes de criar
 * - Fallback por e-mail (se houver)
 * - Criação com idempotência
 * Obs.: em produção persista o mapeamento userId <-> customerId em DB.
 */
@Service
public class BillingCustomerService {
  private static final Logger log = LoggerFactory.getLogger(BillingCustomerService.class);
  private static final String MD_USER_ID = "userId";

  private final Map<String, String> cache = new ConcurrentHashMap<>();
  private final Map<String, Object> locks = new ConcurrentHashMap<>();

  public String findOrCreateCustomer(String userId, String email) throws StripeException {
    if (!StringUtils.hasText(userId)) throw new IllegalArgumentException("userId obrigatório");

    String cached = cache.get(userId);
    if (cached != null) return cached;

    final Object lock = locks.computeIfAbsent(userId, k -> new Object());
    synchronized (lock) {
      cached = cache.get(userId);
      if (cached != null) return cached;

      // 1) tenta encontrar por metadata
      Customer existing = findCustomerByMetadata(userId);

      // 2) fallback por e-mail (se informado)
      if (existing == null && StringUtils.hasText(email)) {
        existing = findBestCustomerByEmail(email);
      }

      if (existing != null) {
        ensureMetadata(existing, userId);
        cache.put(userId, existing.getId());
        log.info("[BILL][CUSTOMER] FOUND userId={} -> stripeCustomerId={}", userId, existing.getId());
        return existing.getId();
      }

      // 3) cria com idempotência por usuário
      RequestOptions ro = RequestOptions.builder()
          .setIdempotencyKey("customer-" + userId)
          .build();

      CustomerCreateParams.Builder cb = CustomerCreateParams.builder()
          .putMetadata(MD_USER_ID, userId);
      if (StringUtils.hasText(email)) cb.setEmail(email);

      Customer created = Customer.create(cb.build(), ro);
      cache.put(userId, created.getId());
      log.info("[BILL][CUSTOMER] CREATED userId={} -> stripeCustomerId={}", userId, created.getId());
      return created.getId();
    }
  }

  private Customer findCustomerByMetadata(String userId) throws StripeException {
    CustomerSearchParams params = CustomerSearchParams.builder()
        .setQuery("metadata['" + MD_USER_ID + "']:'" + escape(userId) + "'")
        .build();
    CustomerSearchResult res = Customer.search(params);
    return (res != null && !res.getData().isEmpty()) ? res.getData().get(0) : null;
  }

  private Customer findBestCustomerByEmail(String email) throws StripeException {
    CustomerSearchParams params = CustomerSearchParams.builder()
        .setQuery("email:'" + escape(email) + "' AND -deleted:'true'")
        .build();
    CustomerSearchResult res = Customer.search(params);
    return (res != null && !res.getData().isEmpty()) ? res.getData().get(0) : null;
  }

  private void ensureMetadata(Customer c, String userId) throws StripeException {
    Map<String, String> md = Optional.ofNullable(c.getMetadata()).orElseGet(ConcurrentHashMap::new);
    String current = md.get(MD_USER_ID);
    if (Objects.equals(current, userId)) return;

    CustomerUpdateParams update = CustomerUpdateParams.builder()
        .putMetadata(MD_USER_ID, userId)
        .build();
    c.update(update);
    log.info("[BILL][CUSTOMER] UPDATED metadata userId for customerId={} ({} -> {})", c.getId(), current, userId);
  }

  private static String escape(String s) { return s == null ? "" : s.replace("'", "\\'"); }

  public void evictCache(String userId) { cache.remove(userId); }
}
