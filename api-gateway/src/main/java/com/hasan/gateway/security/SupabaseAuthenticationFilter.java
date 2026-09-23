package com.hasan.gateway.security;

import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Turns a Supabase token into a user id, and refuses the request when it cannot.
 *
 * <p>This replaces the API-key filter of the gateway it was adapted from. That one authenticated
 * other businesses against a key table it owned; this one authenticates a person against the
 * identity provider their browser already signed them into, so there is no key to store, no
 * registration endpoint, and no database on this service at all.
 *
 * <p><strong>The injected header is stripped from every incoming request before anything else.</strong>
 * {@code budget-core} trusts {@code X-User-Id} completely, so a client that sent its own must not be
 * able to have it forwarded - and the check cannot be "reject requests that carry one", because that
 * would let an attacker probe. It is simply overwritten by whoever the token says is calling, and
 * removed entirely when no token gets through.
 *
 * <p>Two paths are let past deliberately: the CORS preflight, which carries no credentials by
 * definition, and the health check, which Compose polls before any user exists.
 */
@Component
public class SupabaseAuthenticationFilter implements WebFilter, Ordered {

    /** What budget-core reads the caller's identity from, and trusts because only this filter sets it. */
    public static final String USER_HEADER = "X-User-Id";

    /** The rate limiter tracks a signed-in caller by this, rather than by their address. */
    public static final String TRACKING_ID = "rate_limit_tracking_id";

    public static final String CAPACITY = "user_capacity";
    public static final String REFILL_RATE = "user_rate";

    private final ReactiveJwtDecoder tokens;
    private final String capacity;
    private final String refillRate;

    public SupabaseAuthenticationFilter(ReactiveJwtDecoder tokens, GatewayRateLimits limits) {
        this.tokens = tokens;
        this.capacity = String.valueOf(limits.capacity());
        this.refillRate = String.valueOf(limits.refillPerSecond());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // Before anything else, and on every path through this filter - including the two that need no
        // token. Whatever the caller sent under this name is gone from here on; only a checked token
        // can put one back.
        ServerWebExchange sanitised = withoutInjectedUser(exchange);

        if (HttpMethod.OPTIONS.equals(request.getMethod()) || isHealthCheck(request)) {
            return chain.filter(sanitised);
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return refuse(exchange, "Sign in to see this.");
        }

        return tokens.decode(authorization.substring("Bearer ".length()).trim())
                .flatMap(token -> {
                    String userId = token.getSubject();
                    if (userId == null || userId.isBlank()) {
                        return refuse(exchange, "That sign-in is not usable here. Sign in again.");
                    }
                    exchange.getAttributes().put(TRACKING_ID, "user:" + userId);
                    exchange.getAttributes().put(CAPACITY, capacity);
                    exchange.getAttributes().put(REFILL_RATE, refillRate);
                    return chain.filter(sanitised.mutate()
                            .request(sanitised.getRequest().mutate()
                                    .headers(headers -> headers.set(USER_HEADER, userId))
                                    .build())
                            .build());
                })
                // Expired, tampered with, signed by another project's key, or not a token at all:
                // all the same answer. Saying which would help someone work out what to change.
                .onErrorResume(rejected -> refuse(exchange, "Your sign-in has expired. Sign in again."));
    }

    private static boolean isHealthCheck(ServerHttpRequest request) {
        return request.getURI().getPath().startsWith("/actuator/health");
    }

    /** The same request with any caller-supplied user id removed, whoever they turn out to be. */
    private static ServerWebExchange withoutInjectedUser(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> headers.remove(USER_HEADER))
                        .build())
                .build();
    }

    /** RFC 9457, the same shape budget-core answers with, so a client has one error format to read. */
    private static Mono<Void> refuse(ServerWebExchange exchange, String detail) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin != null) {
            // Without these the browser reports a CORS failure instead of the 401, and the client
            // cannot tell "signed out" from "the server is down".
            exchange.getResponse().getHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponse().getHeaders().set("Access-Control-Allow-Credentials", "true");
        }
        String body = """
                {"type":"https://financialbalancer.app/problems/not-signed-in",\
                "title":"Sign in","status":401,"detail":"%s"}"""
                .formatted(detail);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        // Between the global per-address shield and the per-caller limiter, as in the gateway this
        // was adapted from: the limiter below needs the identity this filter establishes.
        return -2;
    }
}
