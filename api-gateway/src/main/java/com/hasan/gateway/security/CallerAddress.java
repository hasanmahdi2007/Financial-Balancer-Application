package com.hasan.gateway.security;

import java.net.InetSocketAddress;
import org.springframework.web.server.ServerWebExchange;

/**
 * Who is calling, as far as the network can say.
 *
 * <p>Both rate limiters worked this out for themselves in the gateway this was adapted from, in two
 * copies of the same lines. One copy, because the two must agree: an address the shield counts under
 * one spelling and the limiter under another is two buckets for one caller.
 *
 * <p><strong>The address is the connection's, never a header's.</strong> The gateway this was adapted
 * from read {@code X-Forwarded-For} first. That header is written by whoever sends the request, and
 * this gateway is the edge - no proxy in front of it vouches for the value - so a caller could claim a
 * fresh address on every request and never meet the per-address limit. Each claimed address also
 * became a bucket in Redis, which made the header a way to fill Redis without signing in.
 *
 * <p>If a trusted proxy is ever placed in front, the fix is not to read the header here again: it is
 * {@code server.forward-headers-strategy: native} with that proxy's address trusted, which rewrites the
 * remote address below from the header only when the proxy is the one who sent it.
 */
final class CallerAddress {

    private CallerAddress() {}

    static String of(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown-ip";
    }
}
