package com.hasan.gateway.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
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
 *
 * <p><strong>It runs first, ahead of the rate limiters and the token check.</strong> Left at its default
 * order it ran last, so a request those filters turned away - a 401, a 429 - went back without CORS
 * headers, the browser reported a CORS failure instead, and the client could not tell "signed out"
 * from "the server is down". The token filter once worked around that by echoing back whatever
 * {@code Origin} arrived, which answered every website on the internet. Running this first means every
 * answer, refusals included, is governed by the one list below and nothing else.
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
        return new FirstCorsWebFilter(source);
    }

    /** Ahead of the per-address shield, which is the earliest of this gateway's own filters. */
    static final class FirstCorsWebFilter extends CorsWebFilter implements Ordered {

        static final int ORDER = -4;

        FirstCorsWebFilter(UrlBasedCorsConfigurationSource source) {
            super(source);
        }

        @Override
        public int getOrder() {
            return ORDER;
        }
    }
}
