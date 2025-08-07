package br.ars.payment_service.services;

import br.ars.payment_service.dto.CreatePaymentRequestDTO;
import br.ars.payment_service.clients.AssinaturaClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final AssinaturaClient assinaturaClient;
    private final EmailService emailService;

    @Value("${stripe.price.id}")
    private String priceId;

    public PaymentService(AssinaturaClient assinaturaClient, EmailService emailService) {
        this.assinaturaClient = assinaturaClient;
        this.emailService = emailService;
    }

    public boolean processarPagamento(CreatePaymentRequestDTO dto) throws StripeException {

        if (assinaturaClient.existeAssinatura(dto.getUserId())) {
            return false;
        }

        // Criação de cliente no Stripe
        Customer customer = Customer.create(CustomerCreateParams.builder()
                .setEmail(dto.getEmail())
                .setPaymentMethod(dto.getPaymentMethodId())
                .setInvoiceSettings(
                        CustomerCreateParams.InvoiceSettings.builder()
                                .setDefaultPaymentMethod(dto.getPaymentMethodId())
                                .build())
                .build());

        // Criação de assinatura
        Subscription subscription = Subscription.create(
            SubscriptionCreateParams.builder()
                .setCustomer(customer.getId())
                .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
                .setPaymentSettings(
                    SubscriptionCreateParams.PaymentSettings.builder()
                        .setSaveDefaultPaymentMethod(
                            SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION
                        ).build())
                // .setExpand(...) REMOVIDO
                .build()
        );


        // Validação da assinatura e envio de e-mail
        if ("active".equals(subscription.getStatus())) {
            emailService.enviarEmail(dto.getEmail(), "Assinatura Confirmada", "Seu pagamento foi aprovado.");
            return true;
        } else {
            emailService.enviarEmail(dto.getEmail(), "Pagamento Falhou", "Houve uma falha no seu pagamento.");
            return false;
        }
    }
}
