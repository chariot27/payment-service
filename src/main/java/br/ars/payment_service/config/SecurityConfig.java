// src/main/java/br/ars/payment_service/config/SecurityConfig.java
package br.ars.payment_service.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Optional;

@Configuration
@EnableConfigurationProperties({ CorsProps.class })
public class SecurityConfig {

  /** Normaliza a configuração de CORS vinda de properties. */
  private static CorsConfiguration buildNormalizedCors(CorsProps props) {
    CorsConfiguration cfg = new CorsConfiguration();

    if (props.allowedOrigins() != null && !props.allowedOrigins().isEmpty()) {
      cfg.setAllowedOrigins(props.allowedOrigins());
    }
    if (props.allowedOriginPatterns() != null && !props.allowedOriginPatterns().isEmpty()) {
      cfg.setAllowedOriginPatterns(props.allowedOriginPatterns());
    }

    cfg.setAllowedMethods(Optional.ofNullable(props.allowedMethods())
        .orElse(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS")));

    cfg.setAllowedHeaders(Optional.ofNullable(props.allowedHeaders())
        .orElse(List.of("*")));

    cfg.setAllowCredentials(Boolean.TRUE.equals(props.allowCredentials())); // default false
    cfg.setMaxAge(Optional.ofNullable(props.maxAge()).orElse(3600L));

    // Blindagem: se credentials=true e allowedOrigins contém "*",
    // migrar para allowedOriginPatterns="*"
    if (Boolean.TRUE.equals(cfg.getAllowCredentials())
        && cfg.getAllowedOrigins() != null
        && cfg.getAllowedOrigins().contains("*")) {
      cfg.setAllowedOrigins(null);
      if (cfg.getAllowedOriginPatterns() == null || cfg.getAllowedOriginPatterns().isEmpty()) {
        cfg.setAllowedOriginPatterns(List.of("*"));
      }
    }

    return cfg;
  }

  /** Bean usado por Spring Security (.cors()). */
  @Bean("corsConfigurationSource")
  public CorsConfigurationSource corsConfigurationSource(CorsProps props) {
    CorsConfiguration cfg = buildNormalizedCors(props);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cfg);
    return source;
  }

  /** Alinha o CORS do MVC ao mesmo usado no Security (evita conflito no DispatcherServlet). */
  @Bean
  public WebMvcConfigurer mvcCors(
      @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) {

    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        UrlBasedCorsConfigurationSource src = (UrlBasedCorsConfigurationSource) corsSource;
        CorsConfiguration cfg = src.getCorsConfigurations().get("/**");
        if (cfg == null) return;

        var reg = registry.addMapping("/**");

        if (cfg.getAllowedOrigins() != null && !cfg.getAllowedOrigins().isEmpty()) {
          reg.allowedOrigins(cfg.getAllowedOrigins().toArray(String[]::new));
        }
        if (cfg.getAllowedOriginPatterns() != null && !cfg.getAllowedOriginPatterns().isEmpty()) {
          reg.allowedOriginPatterns(cfg.getAllowedOriginPatterns().toArray(String[]::new));
        }
        if (cfg.getAllowedMethods() != null && !cfg.getAllowedMethods().isEmpty()) {
          reg.allowedMethods(cfg.getAllowedMethods().toArray(String[]::new));
        }
        if (cfg.getAllowedHeaders() != null && !cfg.getAllowedHeaders().isEmpty()) {
          reg.allowedHeaders(cfg.getAllowedHeaders().toArray(String[]::new));
        }
        reg.allowCredentials(Boolean.TRUE.equals(cfg.getAllowCredentials()));
        if (cfg.getMaxAge() != null) reg.maxAge(cfg.getMaxAge());
      }
    };
  }

  /** Filtro de segurança com CORS habilitado. */
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {

    http
      .csrf(csrf -> csrf.disable())
      .cors(c -> c.configurationSource(corsSource))
      .authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()      // preflight
        .requestMatchers("/actuator/**", "/api/stripe/webhook").permitAll()
        .anyRequest().permitAll()
      );

    return http.build();
  }
}
