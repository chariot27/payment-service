package br.ars.payment_service.service;
import java.util.UUID;

public final class Idem {
  private Idem() {}
  public static String key(String... parts) {
    return String.join(":", parts);
  }
  public static String random() { return UUID.randomUUID().toString(); }
}