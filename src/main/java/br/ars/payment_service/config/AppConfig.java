package br.ars.payment_service.config;

import com.stripe.Stripe;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class AppConfig implements WebMvcConfigurer {

  private final StripeProperties props;

  public AppConfig(StripeProperties props) {
    this.props = props;

    // Configura a secret key global do Stripe
    Stripe.apiKey = props.getSecretKey();

    // Opcional: identifica sua app nos headers do Stripe
    Stripe.setAppInfo("ars-billing", "1.0.0", null);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
      .allowedOrigins(allowedOrigins())
      .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
      .allowCredentials(true);
  }

  private String[] allowedOrigins() {
    // Em produção, restrinja!
    return new String[] { "*" };
  }
}
