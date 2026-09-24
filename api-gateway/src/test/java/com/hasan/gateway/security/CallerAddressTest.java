package com.hasan.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * Who a caller is for rate limiting, when nothing better than their address is known.
 *
 * <p>The address has to be one the caller cannot choose. A limit keyed on something the caller writes
 * is a limit they opt into.
 */
class CallerAddressTest {

    @Test
    @DisplayName("a caller is counted by the connection they came from")
    void theConnectionsAddressIsTheCaller() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/plan")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 51000)));

        assertThat(CallerAddress.of(exchange)).isEqualTo("192.0.2.10");
    }

    @Test
    @DisplayName("an X-Forwarded-For the caller wrote does not change who they are counted as")
    void aForwardedForHeaderCannotChooseTheBucket() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/plan")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 51000))
                .header("X-Forwarded-For", "203.0.113.99, 192.0.2.10"));

        assertThat(CallerAddress.of(exchange))
                .as("this gateway is the edge, so nothing vouches for that header")
                .isEqualTo("192.0.2.10");
    }
}
