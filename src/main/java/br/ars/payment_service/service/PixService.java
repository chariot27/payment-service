// src/main/java/br/ars/payment_service/service/PixService.java
package br.ars.payment_service.service;

import br.ars.payment_service.domain.PaymentStatus;
import br.ars.payment_service.pix.QrGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PixService {

    @Value("${pix.key}")            private String pixKey;
    @Value("${pix.merchant.name}")  private String merchantName;
    @Value("${pix.merchant.city}")  private String merchantCity;
    @Value("${pix.amount}")         private String amountStr; // "49.90"

    public record PixPayload(String copiaECola, String qrBase64) {}

    /** Se você usar QR dinâmico via PSP, altere este método para chamar a API do PSP. */
    public PixPayload build(String ignoredTxid) {
        final String STATIC_TXID = "***";
        final String desc        = "ASSINATURA";

        String payload = buildStaticPix(
                pixKey.trim(),
                merchantName,
                merchantCity,
                new BigDecimal(amountStr),
                desc,
                STATIC_TXID
        );
        String qr = QrGenerator.toBase64Png(payload);
        return new PixPayload(payload, qr);
    }

    /** 🔎 Reconciliação no PSP (implementar quando integrar). Por enquanto, não afirma o pagamento. */
    public PaymentStatus checkStatus(String txid) {
        // TODO integrar com PSP (ex.: PicPay GET /charge/{merchantChargeId}).
        // Enquanto não integra, mantenha "PENDING" para não gerar falsos positivos.
        return PaymentStatus.PENDING;
    }

    // ===== Helpers do EMV estatico =====
    private static String emv(String id, String value) {
        String v = value == null ? "" : value;
        int len = v.getBytes(StandardCharsets.UTF_8).length;
        return id + String.format("%02d", len) + v;
    }
    private static String asciiUpper(String s, int max) {
        if (s == null) return "";
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String up = noAccents.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 \\-\\.]", "");
        return up.length() > max ? up.substring(0, max) : up;
    }
    private static String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
    private static String buildStaticPix(String key, String name, String city, BigDecimal amount, String desc, String txidStars) {
        String mName = asciiUpper(name, 25);
        String mCity = asciiUpper(city, 15);
        String amt   = formatAmount(amount);
        String mDesc = desc == null ? "" : asciiUpper(desc, 25);

        String mai = emv("00", "br.gov.bcb.pix")
                + emv("01", key)
                + (mDesc.isBlank() ? "" : emv("02", mDesc));

        String base = emv("00", "01")
                + emv("01", "11")
                + emv("26", mai)
                + emv("52", "0000")
                + emv("53", "986")
                + emv("54", amt)
                + emv("58", "BR")
                + emv("59", mName)
                + emv("60", mCity)
                + emv("62", emv("05", txidStars));

        String toCrc = base + "6304";
        return toCrc + crc16(toCrc);
    }
    private static String crc16(String s) {
        int crc = 0xFFFF;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++)
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return String.format("%04X", crc);
    }
}
