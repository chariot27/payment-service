package br.ars.payment_service.config;


import com.stripe.Stripe;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class AppConfig implements WebMvcConfigurer {

  private final StripeProperties props;
  public AppConfig(StripeProperties props) {
    this.props = props;
    // Configura uma vez a Secret Key da Stripe
    Stripe.apiKey = props.secretKey();
    if (props.apiVersion() != null && !props.apiVersion().isBlank()) {
      Stripe.setAppInfo("ars-billing", "1.0.0", null);
    }
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
      .allowedOrigins(propsAllowedOrigins())
      .allowedMethods("GET","POST","PUT","DELETE","OPTIONS")
      .allowCredentials(true);
  }

  private String[] propsAllowedOrigins() {
    // você pode carregar de app.cors.allowed-origins via @Value também
    return new String[]{"*"}; // ajuste em prod
  }
}

