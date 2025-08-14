// br/ars/payment_service/config/CorsProps.java
package br.ars.payment_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "app.cors")
public record CorsProps(
  List<String> allowedOrigins,
  List<String> allowedOriginPatterns,
  List<String> allowedMethods,
  List<String> allowedHeaders,
  Boolean allowCredentials,
  Long maxAge
) {}
