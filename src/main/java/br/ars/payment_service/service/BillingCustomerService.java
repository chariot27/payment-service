package br.ars.payment_service.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação simples em memória para mapear userId -> stripeCustomerId.
 * Substitua pelo seu serviço que persiste em banco.
 */
@Service
public class BillingCustomerService {
  private static final Logger log = LoggerFactory.getLogger(BillingCustomerService.class);

  private final Map<String, String> cache = new ConcurrentHashMap<>();

  public String findOrCreateCustomer(String userId, String email) throws StripeException {
    if (cache.containsKey(userId)) return cache.get(userId);

    CustomerCreateParams.Builder b = CustomerCreateParams.builder()
        .putMetadata("userId", userId);
    if (StringUtils.hasText(email)) b.setEmail(email);

    Customer c = Customer.create(b.build());
    cache.put(userId, c.getId());
    log.info("[BILL][CUSTOMER] userId={} -> stripeCustomerId={}", userId, c.getId());
    return c.getId();
  }
}
