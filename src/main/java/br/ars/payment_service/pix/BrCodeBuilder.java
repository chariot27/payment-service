package br.ars.payment_service.pix;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Constrói payload EMV do PIX ("copia e cola") com CRC16-CCITT (0x1021). */
public final class BrCodeBuilder {
    private BrCodeBuilder() {}

    private static String tlv(String id, String value) {
        String len = String.format(Locale.ROOT, "%02d",
                value.getBytes(StandardCharsets.UTF_8).length);
        return id + len + value;
    }

    private static String mai(String pixKey, String description) {
        String gui = tlv("00", "br.gov.bcb.pix");
        String key = tlv("01", pixKey);
        String desc = (description != null && !description.isBlank()) ? tlv("02", description) : "";
        return tlv("26", gui + key + desc);
    }

    private static String addDataField(String txid) {
        return tlv("62", tlv("05", txid));
    }

    private static String crc16(String data) {
        int polynomial = 0x1021;
        int result = 0xFFFF;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            result ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((result & 0x8000) != 0) result = (result << 1) ^ polynomial;
                else result = (result << 1);
                result &= 0xFFFF;
            }
        }
        return String.format(Locale.ROOT, "%04X", result);
    }

    public static String buildPayload(String pixKey, String merchantName, String merchantCity,
                                      BigDecimal amount, String txid, boolean dynamic) {
        String format = tlv("00", "01");
        String poi = tlv("01", dynamic ? "12" : "11");
        String merchantInfo = mai(pixKey, "ASSINATURA");
        String mcc = tlv("52", "0000");
        String currency = tlv("53", "986");
        String amt = tlv("54", amount.setScale(2).toPlainString());
        String country = tlv("58", "BR");
        String name = tlv("59", sanitize(merchantName, 25));
        String city = tlv("60", sanitize(merchantCity, 15));
        String add = addDataField(txid);

        String partial = format + poi + merchantInfo + mcc + currency + amt + country + name + city + add;
        String toCrc = partial + "6304";
        String crc = crc16(toCrc);
        return partial + "63" + "04" + crc;
    }

    private static String sanitize(String s, int max) {
        if (s == null) s = "NA";
        s = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 \\-\\.]", "");
        if (s.length() > max) s = s.substring(0, max);
        return s;
    }
}
