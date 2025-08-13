package br.ars.payment_service.service;

import br.ars.payment_service.pix.BrCodeBuilder;
import br.ars.payment_service.pix.QrGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PixService {

    @Value("${pix.key}")          private String pixKey;
    @Value("${pix.merchant.name}") private String merchantName;
    @Value("${pix.merchant.city}") private String merchantCity;
    @Value("${pix.amount}")        private String amountStr;

    public record PixPayload(String copiaECola, String qrBase64) {}

    public PixPayload build(String ignoredTxidFromApp) {
        // QR estático: TXID deve ser "***" por norma
        final String STATIC_TXID = "***";

        var amount = new BigDecimal(amountStr);

        // IMPORTANTE: último parâmetro = dynamic=false
        String payload = BrCodeBuilder.buildPayload(
                pixKey,
                merchantName,
                merchantCity,
                amount,
                STATIC_TXID,
                /* dynamic */ false
        );

        String qr = QrGenerator.toBase64Png(payload);
        return new PixPayload(payload, qr);
    }
}

