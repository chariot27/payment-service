package br.ars.payment_service.pix;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

public final class QrGenerator {
    private QrGenerator(){}

    public static String toBase64Png(String payload) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            var matrix = writer.encode(payload, BarcodeFormat.QR_CODE, 512, 512);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (WriterException | java.io.IOException e) {
            throw new RuntimeException("QR generation failed", e);
        }
    }
}
