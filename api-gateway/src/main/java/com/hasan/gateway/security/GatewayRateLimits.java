package com.hasan.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What one signed-in person may ask for, and how fast.
 *
 * <p>The gateway this was adapted from had tiers, because its callers were other businesses paying
 * for throughput. Here every caller is one person using their own dashboard, so there is one set of
 * numbers - carried as configuration rather than constants so they can be raised without a rebuild
 * when a screen turns out to need more calls than expected.
 *
 * @param capacity the burst a caller may make at once
 * @param refillPerSecond how quickly that capacity comes back
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record GatewayRateLimits(int capacity, int refillPerSecond) {

    public GatewayRateLimits {
        if (capacity < 1 || refillPerSecond < 1) {
            throw new IllegalArgumentException("a rate limit of zero would refuse every request");
        }
    }
}
