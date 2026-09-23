package com.hasan.gateway.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Which browser origins may call this gateway.
 *
 * <p>Carried across from the gateway this was adapted from, with the origins changed to the client in
 * this project. The allowed headers include {@code Authorization}, which is how the browser sends the
 * Supabase token; {@code X-User-Id} is deliberately not something a browser is invited to send, since
 * this gateway sets it and overwrites anything that arrives.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        // Vite's dev server, and the port used when the client is served statically.
        cors.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowCredentials(true);
        // An hour, so a browser is not sent to ask again before every request it makes.
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source);
    }
}
