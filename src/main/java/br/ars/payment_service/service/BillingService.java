package br.ars.payment_service.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.SubscriptionRetrieveParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    // =====================================================================
    // >>> OUTROS CAMPOS/INJEÇÕES/MÉTODOS DO SEU SERVIÇO PERMANECEM IGUAIS <<
    // =====================================================================

    // ---------------------------------------------------------------------
    // START: ajuste de UMA linha dentro do seu startSubscription(...)
    // Procure pela linha que chamava "fetchPIClientSecretBlocking(sub, Duration.ofSeconds(30))"
    // e troque por ESTA VERSÃO (passando customerId e timeout 45s):
    //
    // String piClientSecret = fetchPIClientSecretBlocking(subscription, customerId, Duration.ofSeconds(45));
    //
    // ---------------------------------------------------------------------

    /**
     * Faz polling para obter o client_secret do PaymentIntent associado à assinatura.
     * Suporta diferenças entre versões do SDK da Stripe:
     * - Quando a expansão retorna apenas o ID do PI (string)
     * - Quando retorna o objeto do PI, porém sem lastResponse/JSON
     * - Quando é necessário reconsultar (retrieve) o PI para obter o client_secret
     * Inclui fallback por listagem de PIs do cliente para casar pela invoice.
     */
    private String fetchPIClientSecretBlocking(Subscription initial, String customerId, Duration maxWait) throws StripeException {
        final long deadline = System.nanoTime() + maxWait.toNanos();
        int attempt = 0;
        Subscription sub = initial;

        while (System.nanoTime() < deadline) {
            attempt++;

            try {
                // (A) Retrieve da assinatura com expands
                sub = Subscription.retrieve(
                    sub.getId(),
                    SubscriptionRetrieveParams.builder()
                        .addExpand("latest_invoice")
                        .addExpand("latest_invoice.payment_intent")
                        .build(),
                    null
                );

                String latestInvoiceId = null;
                try { latestInvoiceId = sub.getLatestInvoice(); } catch (Throwable ignore) {}

                Invoice inv = null;
                try { inv = sub.getLatestInvoiceObject(); } catch (Throwable ignore) {}

                // 1) Tenta extrair o ID do PI a partir da invoice (compatível com SDKs diferentes)
                String piId = tryGetPaymentIntentId(inv);
                if (piId != null) {
                    // Re-retrieve garante objeto "fresh" com lastResponse e client_secret
                    PaymentIntent pi = PaymentIntent.retrieve(piId);
                    String cs = tryExtractClientSecret(pi);
                    if (hasText(cs)) {
                        log.info("[BILL][PI] CS via PI.retrieve (tentativa {}): pi={}", attempt, piId);
                        return cs;
                    }
                }

                // 2) Tenta pelo JSON cru da Invoice (quando existe lastResponse no expand)
                if (inv != null) {
                    String cs = tryExtractClientSecretFromInvoiceRaw(inv);
                    if (hasText(cs)) {
                        log.info("[BILL][PI] CS via Invoice RAW (tentativa {}) inv={}", attempt, inv.getId());
                        return cs;
                    }
                } else {
                    // 3) Como último recurso, tenta pelo JSON cru da Subscription
                    String cs = tryExtractClientSecretFromSubscriptionRaw(sub);
                    if (hasText(cs)) {
                        log.info("[BILL][PI] CS via Subscription RAW (tentativa {}) sub={}", attempt, sub.getId());
                        return cs;
                    }
                }

                // (B) Retrieve direto da invoice + expand payment_intent e então retrieve do PI
                if (hasText(latestInvoiceId)) {
                    Invoice inv2 = Invoice.retrieve(
                        latestInvoiceId,
                        InvoiceRetrieveParams.builder().addExpand("payment_intent").build(),
                        null
                    );

                    String piId2 = tryGetPaymentIntentId(inv2);
                    if (piId2 != null) {
                        PaymentIntent pi2 = PaymentIntent.retrieve(piId2);
                        String cs = tryExtractClientSecret(pi2);
                        if (hasText(cs)) {
                            log.info("[BILL][PI] CS via Invoice.retrieve→PI.retrieve (tentativa {}) pi={}", attempt, piId2);
                            return cs;
                        }
                    }

                    String cs = tryExtractClientSecretFromInvoiceRaw(inv2);
                    if (hasText(cs)) {
                        log.info("[BILL][PI] CS via Invoice.retrieve RAW (tentativa {}) inv={}", attempt, inv2.getId());
                        return cs;
                    }
                }

                // (C) Fallback por listagem de PIs do cliente e casamento pela invoice
                if (hasText(customerId)) {
                    try {
                        String cs = findClientSecretByListingPIs(customerId, sub.getLatestInvoice());
                        if (hasText(cs)) {
                            log.info("[BILL][PI] CS via PaymentIntent.list (tentativa {})", attempt);
                            return cs;
                        }
                    } catch (Throwable t) {
                        log.debug("[BILL][PI] list fallback falhou: {}", t.getMessage());
                    }
                }
            } catch (Throwable t) {
                log.debug("[BILL][PI] tentativa {} falhou: {}", attempt, t.toString());
            }

            // backoff progressivo (até 2s)
            long backoffMs = Math.min(2000L, 250L * attempt);
            try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        log.warn("[BILL][PI-RETRY] Timeout sem obter client_secret para sub {}", initial.getId());
        return null;
    }

    // ===========================
    // Helpers utilitários (novos)
    // ===========================

    private static boolean hasText(String s) { return s != null && !s.isEmpty(); }

    /**
     * Extrai o ID do PaymentIntent a partir de uma Invoice, cobrindo:
     * - inv.getPaymentIntentObject() (objeto expandido)
     * - inv.getPaymentIntent() (String ID) — pode não existir em alguns SDKs
     * - JSON cru da Invoice (lastResponse), quando disponível
     */
    private static String tryGetPaymentIntentId(Invoice inv) {
        if (inv == null) return null;

        // 1) Tenta via objeto expandido
        try {
            Method mObj = Invoice.class.getMethod("getPaymentIntentObject");
            Object piObj = mObj.invoke(inv);
            if (piObj instanceof PaymentIntent pi) {
                try {
                    String id = pi.getId();
                    if (hasText(id)) return id;
                } catch (Throwable ignored) {
                    try {
                        Method mid = pi.getClass().getMethod("getId");
                        Object v = mid.invoke(pi);
                        if (v instanceof String s && hasText(s)) return s;
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}

        // 2) Tenta via getter antigo que retorna String
        try {
            Method mId = Invoice.class.getMethod("getPaymentIntent");
            Object idObj = mId.invoke(inv);
            if (idObj instanceof String s && hasText(s)) return s;
        } catch (Throwable ignored) {}

        // 3) Tenta via JSON cru (lastResponse)
        try {
            Object lastResp = invokeNoArg(inv, "getLastResponse");
            String body = extractBody(lastResp);
            if (body != null) {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonElement pie = root.get("payment_intent");
                if (pie != null && !pie.isJsonNull()) {
                    if (pie.isJsonObject()) {
                        JsonElement idEl = pie.getAsJsonObject().get("id");
                        if (idEl != null && !idEl.isJsonNull()) return idEl.getAsString();
                    } else if (pie.isJsonPrimitive()) {
                        return pie.getAsString();
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Tenta extrair o client_secret de um PaymentIntent. Se necessário,
     * faz um retrieve "fresh" baseado no id e tenta pelo JSON cru.
     */
    private static String tryExtractClientSecret(PaymentIntent pi) {
        if (pi == null) return null;

        // getter direto (SDKs recentes)
        try {
            String cs = pi.getClientSecret();
            if (hasText(cs)) return cs;
        } catch (Throwable ignored) {}

        // reflection (SDKs antigas)
        try {
            Method m = pi.getClass().getMethod("getClientSecret");
            Object val = m.invoke(pi);
            if (val instanceof String s && hasText(s)) return s;
        } catch (Throwable ignored) {}

        // Se veio de expansão e não tem body, re-retrieve pelo id
        try {
            String id = null;
            try { id = pi.getId(); } catch (Throwable ignored) {
                try {
                    Method mid = pi.getClass().getMethod("getId");
                    Object v = mid.invoke(pi);
                    if (v instanceof String s) id = s;
                } catch (Throwable ignored2) {}
            }
            if (hasText(id)) {
                PaymentIntent fresh = PaymentIntent.retrieve(id);

                try {
                    String cs2 = fresh.getClientSecret();
                    if (hasText(cs2)) return cs2;
                } catch (Throwable ignored) {}

                Object lastResp = invokeNoArg(fresh, "getLastResponse");
                String body = extractBody(lastResp);
                if (body != null) {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonElement cse = root.get("client_secret");
                    if (cse != null && !cse.isJsonNull()) {
                        String s = cse.getAsString();
                        if (hasText(s)) return s;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Última chance: JSON cru do próprio objeto recebido
        try {
            Object lastResp = invokeNoArg(pi, "getLastResponse");
            String body = extractBody(lastResp);
            if (body != null) {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonElement cse = root.get("client_secret");
                if (cse != null && !cse.isJsonNull()) {
                    String s = cse.getAsString();
                    if (hasText(s)) return s;
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Fallback: lista PIs do cliente e tenta achar o que pertence à invoice destino.
     * Retorna o client_secret se encontrar.
     */
    private static String findClientSecretByListingPIs(String customerId, String targetInvoiceId) throws StripeException {
        if (!hasText(customerId)) return null;

        com.stripe.param.PaymentIntentListParams.Builder b =
            com.stripe.param.PaymentIntentListParams.builder()
                .setCustomer(customerId)
                .setLimit(10L)
                .addExpand("data.invoice");

        com.stripe.model.PaymentIntentCollection col = PaymentIntent.list(b.build());
        if (col == null || col.getData() == null) return null;

        // 1) Tenta casar explicitamente pela invoice
        for (PaymentIntent pi : col.getData()) {
            String invoiceId = null;
            try {
                invoiceId = (pi.getInvoiceObject() != null)
                    ? pi.getInvoiceObject().getId()
                    : pi.getInvoice();
            } catch (Throwable ignored) {}

            if (hasText(targetInvoiceId) && hasText(invoiceId) && targetInvoiceId.equals(invoiceId)) {
                PaymentIntent fresh = PaymentIntent.retrieve(pi.getId());
                String cs = tryExtractClientSecret(fresh);
                if (hasText(cs)) return cs;
            }
        }

        // 2) Se não houver targetInvoiceId, pega o mais novo com invoice não nulo
        for (PaymentIntent pi : col.getData()) {
            String invoiceId = null;
            try {
                invoiceId = (pi.getInvoiceObject() != null)
                    ? pi.getInvoiceObject().getId()
                    : pi.getInvoice();
            } catch (Throwable ignored) {}
            if (hasText(invoiceId)) {
                PaymentIntent fresh = PaymentIntent.retrieve(pi.getId());
                String cs = tryExtractClientSecret(fresh);
                if (hasText(cs)) return cs;
            }
        }
        return null;
    }

    /**
     * Extrai client_secret do JSON cru da Invoice, quando disponível.
     */
    private static String tryExtractClientSecretFromInvoiceRaw(Invoice inv) {
        try {
            Object lastResp = invokeNoArg(inv, "getLastResponse");
            String body = extractBody(lastResp);
            if (body == null) return null;

            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement pie = root.get("payment_intent");
            if (pie == null || pie.isJsonNull()) return null;

            if (pie.isJsonObject()) {
                JsonElement cse = pie.getAsJsonObject().get("client_secret");
                if (cse != null && !cse.isJsonNull()) {
                    String s = cse.getAsString();
                    return hasText(s) ? s : null;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Extrai client_secret a partir do JSON cru da Subscription (expand), quando disponível.
     */
    private static String tryExtractClientSecretFromSubscriptionRaw(Subscription sub) {
        try {
            Object lastResp = invokeNoArg(sub, "getLastResponse");
            String body = extractBody(lastResp);
            if (body == null) return null;

            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject latestInvoice = root.has("latest_invoice") && root.get("latest_invoice").isJsonObject()
                ? root.getAsJsonObject("latest_invoice")
                : null;

            if (latestInvoice == null) return null;

            JsonElement pie = latestInvoice.get("payment_intent");
            if (pie == null || pie.isJsonNull()) return null;

            if (pie.isJsonObject()) {
                JsonElement cse = pie.getAsJsonObject().get("client_secret");
                if (cse != null && !cse.isJsonNull()) {
                    String s = cse.getAsString();
                    return hasText(s) ? s : null;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Invoca método sem argumentos por reflexão.
     */
    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Extrai a string do corpo a partir de um ApiResponse/StripeResponse,
     * cobrindo variações do SDK (método body(), getBody() ou campo "body").
     */
    private static String extractBody(Object lastResponse) {
        if (lastResponse == null) return null;
        try {
            // método body()
            Method bodyM = lastResponse.getClass().getMethod("body");
            bodyM.setAccessible(true);
            Object v = bodyM.invoke(lastResponse);
            if (v instanceof String s && hasText(s)) return s;
        } catch (Throwable ignored) {}

        try {
            // método getBody()
            Method bodyM2 = lastResponse.getClass().getMethod("getBody");
            bodyM2.setAccessible(true);
            Object v = bodyM2.invoke(lastResponse);
            if (v instanceof String s && hasText(s)) return s;
        } catch (Throwable ignored) {}

        try {
            // campo "body"
            Field f = lastResponse.getClass().getDeclaredField("body");
            f.setAccessible(true);
            Object v = f.get(lastResponse);
            if (v instanceof String s && hasText(s)) return s;
        } catch (Throwable ignored) {}

        return null;
    }

    // =====================================================================
    // >>> OUTROS MÉTODOS DO SERVIÇO (create subscription, cancel, status etc)
    //     permanecem como já estão no seu projeto.
    //     Garanta apenas que, dentro de startSubscription(...),
    //     a chamada ao polling ficou assim:
    //
    //     String piClientSecret = fetchPIClientSecretBlocking(subscription, customerId, Duration.ofSeconds(45));
    //
    // =====================================================================
}
