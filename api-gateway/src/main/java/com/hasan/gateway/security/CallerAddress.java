package com.hasan.gateway.security;

import org.springframework.web.server.ServerWebExchange;

/**
 * Who is calling, as far as the network can say.
 *
 * <p>Both rate limiters worked this out for themselves in the gateway this was adapted from, in two
 * copies of the same lines. One copy, because the two must agree: an address the shield counts under
 * one spelling and the limiter under another is two buckets for one caller.
 */
final class CallerAddress {

    private CallerAddress() {}

    static String of(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown-ip";
    }
}
