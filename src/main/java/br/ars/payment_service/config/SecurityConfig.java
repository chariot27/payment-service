// src/main/java/br/ars/payment_service/config/SecurityConfig.java
package br.ars.payment_service.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Optional;

@Configuration
@EnableConfigurationProperties({ CorsProps.class })
public class SecurityConfig {

  @Bean("corsConfigurationSource")
  public CorsConfigurationSource corsConfigurationSource(CorsProps props) {
    CorsConfiguration cfg = new CorsConfiguration();

    // Origens exatas
    if (props.allowedOrigins() != null && !props.allowedOrigins().isEmpty()) {
      cfg.setAllowedOrigins(props.allowedOrigins());
    }
    // Padrões de origem (ex.: http://192.168.*:* , exp://*)
    if (props.allowedOriginPatterns() != null && !props.allowedOriginPatterns().isEmpty()) {
      cfg.setAllowedOriginPatterns(props.allowedOriginPatterns());
    }

    cfg.setAllowedMethods(Optional.ofNullable(props.allowedMethods())
        .orElse(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")));

    cfg.setAllowedHeaders(Optional.ofNullable(props.allowedHeaders())
        .orElse(List.of("*")));

    cfg.setAllowCredentials(Boolean.TRUE.equals(props.allowCredentials())); // default false
    cfg.setMaxAge(Optional.ofNullable(props.maxAge()).orElse(3600L));

    // ⚠️ Blindagem: se credentials=true e allowedOrigins contém "*",
    // migra para allowedOriginPatterns="*"
    if (Boolean.TRUE.equals(cfg.getAllowCredentials())
        && cfg.getAllowedOrigins() != null
        && cfg.getAllowedOrigins().contains("*")) {
      cfg.setAllowedOrigins(null);
      if (cfg.getAllowedOriginPatterns() == null || cfg.getAllowedOriginPatterns().isEmpty()) {
        cfg.setAllowedOriginPatterns(List.of("*"));
      }
    }

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cfg);
    return source;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {

    http
      .csrf(csrf -> csrf.disable())
      .cors(c -> c.configurationSource(corsSource)) // usa o bean certo
      .authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()   // preflight
        .requestMatchers("/actuator/**", "/api/stripe/webhook").permitAll()
        .anyRequest().permitAll()
      );

    return http.build();
  }
}
