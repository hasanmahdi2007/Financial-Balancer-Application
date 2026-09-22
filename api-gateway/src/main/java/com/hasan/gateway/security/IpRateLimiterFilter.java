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
 * The outer shield: one token bucket per calling address, ahead of any authentication.
 *
 * <p>Carried across unchanged from the gateway this was adapted from, including its limits. It runs
 * before the token is checked on purpose - checking a signature is work, and something flooding the
 * door should be turned away before it can make this service do any.
 */
@Component
public class IpRateLimiterFilter implements WebFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;

    public IpRateLimiterFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        this.script.setResultType(Long.class);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        String ipAddress = CallerAddress.of(exchange);

        String capacity = "3000";
        String rate = "200";
        String now = String.valueOf(Instant.now().getEpochSecond());
        String requested = "1";

        List<String> keys = List.of("ip_tokens:" + ipAddress, "ip_timestamp:" + ipAddress);
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
        return -3;
    }
}
