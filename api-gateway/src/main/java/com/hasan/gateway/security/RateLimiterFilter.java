package com.hasan.gateway.security;

import java.time.Instant;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The inner limit: one token bucket per caller, using the same Lua script and the same mechanism as
 * the gateway this was adapted from.
 *
 * <p>What changed is only who a caller is. There it was an API key, taken straight from a header,
 * because the callers were other businesses. Here it is the user the filter above established from a
 * signed token - which is also a stronger identity than a header was, since a caller cannot choose
 * it. Anything that reaches this filter without one is rate-limited by address, which is the most
 * that can be known about it.
 */
@Component
public class RateLimiterFilter implements WebFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;

    public RateLimiterFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        this.script.setResultType(Long.class);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        String trackingId = exchange.getAttributeOrDefault(
                SupabaseAuthenticationFilter.TRACKING_ID, "anon_ip:" + CallerAddress.of(exchange));

        String capacity = exchange.getAttributeOrDefault(SupabaseAuthenticationFilter.CAPACITY, "15");
        String rate = exchange.getAttributeOrDefault(SupabaseAuthenticationFilter.REFILL_RATE, "2");

        String now = String.valueOf(Instant.now().getEpochSecond());
        String requested = "1";

        List<String> keys = List.of("tokens:" + trackingId, "timestamp:" + trackingId);
        List<String> args = List.of(rate, capacity, now, requested);

        return redisTemplate.execute(script, keys, args)
                .next()
                .flatMap(result -> {
                    if (result == 1L) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();
                });
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
