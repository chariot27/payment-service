package br.ars.payment_service.service;

import br.ars.payment_service.pix.BrCodeBuilder;
import br.ars.payment_service.pix.QrGenerator;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PixService {

    @Value("${pix.key}")            private String pixKey;        // CHAVE REAL REGISTRADA
    @Value("${pix.merchant.name}")  private String merchantName;  // máx 25 chars (ASCII)
    @Value("${pix.merchant.city}")  private String merchantCity;  // máx 15 chars (ASCII)
    @Value("${pix.amount}")         private String amountStr;     // ex: "49.90"

    public record PixPayload(String copiaECola, String qrBase64) {}

    public PixPayload build(String ignoredTxid) {
        final String STATIC_TXID = "***";              // estático => use "***"
        final String desc = "ASSINATURA";              // opcional, curto

        String payload = buildStaticPix(
                pixKey.trim(),
                merchantName.trim(),
                merchantCity.trim(),
                new BigDecimal(amountStr),
                desc,
                STATIC_TXID
        );

        String qr = QrGenerator.toBase64Png(payload);
        return new PixPayload(payload, qr);
    }

    // ===== Helpers EMV =====
    private static String emv(String id, String value) {
        String len = String.format("%02d", value.length());
        return id + len + value;
    }

    private static String crc16(String s) {
        int crc = 0xFFFF;
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i=0;i<8;i++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }

    /** QR estático com chave (sem URL) — 010212 */
    private static String buildStaticPix(String key, String name, String city, BigDecimal amount, String desc, String txidStars) {
        // Merchant Account Info (ID 26)
        String mai = emv("00","br.gov.bcb.pix")
                   + emv("01", key)                      // CHAVE PIX válida/registrada
                   + (desc != null && !desc.isBlank() ? emv("02", desc) : "");

        String base = emv("00","01")                     // Payload Format Indicator
                    + emv("01","12")                     // <<< ESTÁTICO
                    + emv("26", mai)
                    + emv("52","0000")
                    + emv("53","986")
                    + emv("54", amount.stripTrailingZeros().toPlainString())
                    + emv("58","BR")
                    + emv("59", name)
                    + emv("60", city)
                    + emv("62", emv("05", txidStars));   // "***" para estático

        String toCrc = base + "6304";
        return toCrc + crc16(toCrc);
    }
}

