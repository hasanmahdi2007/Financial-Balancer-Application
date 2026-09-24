package com.hasan.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import reactor.core.publisher.Flux;

/**
 * The gateway as it actually runs: the filters, the routes and Redis, with a stub standing in for
 * budget-core.
 *
 * <p>The unit tests cover what the authentication filter decides. This covers whether any of it is
 * wired - that the filter is in the chain at all, that a request it lets through is really forwarded
 * with the injected id, and that one it refuses never reaches the service behind it. A filter that
 * works perfectly and is not registered looks exactly like a filter that works.
 *
 * <p>Redis comes from Testcontainers on its own random port, so this never touches the shared local
 * stack. Nothing here reaches Supabase: the decoder is replaced with one holding a key pair generated
 * in the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(GatewayRoutingIT.LocalSigningKey.class)
class GatewayRoutingIT {

    private static final String ISSUER = "https://project.supabase.co/auth/v1";
    private static final String SOMEONE = "8a1f6b7c-0000-4000-8000-1234567890ab";

    private static final ECKey SIGNING_KEY = generateKey();
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private static final HttpServer BUDGET_CORE = stubBudgetCore();

    /** Every request the stub received, so the test can assert what did and did not reach it. */
    private static final List<HttpHeaders> RECEIVED = new CopyOnWriteArrayList<>();

    static {
        REDIS.start();
        BUDGET_CORE.start();
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // The placeholder the route already reads, rather than the route's own uri property: a list
        // binds from one source only, so setting routes[0].uri here would silently replace the whole
        // route - predicates and all - with a fragment of one.
        registry.add("BUDGET_CORE_URI", () -> "http://127.0.0.1:" + BUDGET_CORE.getAddress().getPort());
        // Never fetched: the decoder below replaces the one this would build.
        registry.add("supabase.jwks-uri", () -> "https://example.invalid/jwks.json");
        registry.add("supabase.issuer", () -> ISSUER);
    }

    @AfterAll
    static void stopStub() {
        BUDGET_CORE.stop(0);
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @BeforeEach
    void forgetEarlierRequests() {
        RECEIVED.clear();
    }

    @Test
    @DisplayName("a request with no token is refused at the gateway and never reaches budget-core")
    void anUnauthenticatedRequestNeverReachesTheDomain() {
        client.get().uri("/api/v1/plan").exchange().expectStatus().isUnauthorized();

        assertThat(RECEIVED).as("budget-core must never see a request nobody signed in for").isEmpty();
    }

    @Test
    @DisplayName("a signed-in request arrives at budget-core carrying the id from its token")
    void aSignedInRequestArrivesWithTheTrustedUserId() throws Exception {
        client.get()
                .uri("/api/v1/plan")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SOMEONE))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.getFirst().get(SupabaseAuthenticationFilter.USER_HEADER)).containsExactly(SOMEONE);
    }

    @Test
    @DisplayName("a user id the caller sent themselves is replaced before the request is forwarded")
    void aForgedUserIdNeverReachesBudgetCore() throws Exception {
        client.get()
                .uri("/api/v1/plan")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SOMEONE))
                .header(SupabaseAuthenticationFilter.USER_HEADER, "somebody-elses-account")
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(RECEIVED.getFirst().get(SupabaseAuthenticationFilter.USER_HEADER))
                .as("budget-core trusts this header, so only the gateway may set it")
                .containsExactly(SOMEONE);
    }

    /**
     * The rate limiting came across from the gateway this was adapted from and still works - but who
     * a caller <em>is</em> changed, from an API key they sent to the user their token proves. This is
     * the test of that change: one person exhausting their own allowance must not touch anybody
     * else's, which is exactly what would happen if both fell back to being tracked by address.
     */
    @Test
    @DisplayName("a caller is rate-limited as themselves, and not on anyone else's behalf")
    void oneCallersLimitIsTheirOwn() throws Exception {
        String busy = "11111111-0000-4000-8000-aaaaaaaaaaaa";
        String quiet = "22222222-0000-4000-8000-bbbbbbbbbbbb";

        int refused = 0;
        for (int request = 0; request < 40; request++) {
            int status = client.get()
                    .uri("/api/v1/plan")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(busy))
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
            if (status == 429) {
                refused++;
            }
        }
        assertThat(refused).as("a caller past their allowance is turned away").isPositive();

        client.get()
                .uri("/api/v1/plan")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(quiet))
                .exchange()
                .expectStatus()
                .isOk();
    }

    /**
     * A caller-written X-Forwarded-For once chose the rate-limit bucket, so one client could rotate the
     * header and never be limited - and every value it invented stayed in Redis as a key.
     */
    @Test
    @DisplayName("an address the caller claims does not get a rate-limit bucket of its own")
    void aClaimedAddressIsNotABucket() {
        client.get().uri("/api/v1/plan").header("X-Forwarded-For", "203.0.113.77").exchange();

        assertThat(redis.hasKey("ip_tokens:203.0.113.77").block()).isFalse();
        assertThat(redis.hasKey("ip_tokens:127.0.0.1").block()).isTrue();
    }

    /** A bucket nobody expires is a caller remembered forever, which is Redis memory that only grows. */
    @Test
    @DisplayName("rate-limit state expires once it no longer means anything")
    void rateLimitStateExpires() throws Exception {
        String caller = "33333333-0000-4000-8000-cccccccccccc";
        client.get().uri("/api/v1/plan").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(caller)).exchange();

        for (String key : List.of(
                "ip_tokens:127.0.0.1", "ip_timestamp:127.0.0.1", "tokens:user:" + caller, "timestamp:user:" + caller)) {
            assertThat(redis.getExpire(key).block())
                    .as("%s must expire", key)
                    .isNotNull()
                    .isPositive();
        }
    }

    /**
     * Refusals used to copy back whatever Origin arrived, with credentials allowed, so every website
     * was told it could read this gateway's answers. The allowed origins are a list, and a refusal is
     * an answer like any other.
     */
    @Test
    @DisplayName("a refusal is readable by the client's own origin and by no other")
    void aRefusalAnswersOnlyTheAllowedOrigin() {
        client.get()
                .uri("/api/v1/plan")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");

        client.get()
                .uri("/api/v1/plan")
                .header(HttpHeaders.ORIGIN, "https://somewhere-else.example")
                .exchange()
                .expectHeader()
                .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);

        assertThat(RECEIVED).isEmpty();
    }

    /** The decoder, holding a key generated here rather than fetched from Supabase. */
    @TestConfiguration
    static class LocalSigningKey {

        @Bean
        @Primary
        ReactiveJwtDecoder testDecoder() {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                    .withJwkSource(signed -> Flux.just(SIGNING_KEY.toPublicJWK()))
                    .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256)
                    .build();
            decoder.setJwtValidator(SupabaseTokens.validator(ISSUER, "authenticated"));
            return decoder;
        }
    }

    private static HttpServer stubBudgetCore() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                HttpHeaders headers = new HttpHeaders();
                exchange.getRequestHeaders().forEach(headers::addAll);
                RECEIVED.add(headers);
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            return server;
        } catch (IOException unavailable) {
            throw new IllegalStateException("could not start the stub budget-core", unavailable);
        }
    }

    private static ECKey generateKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("supabase").generate();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String token(String subject) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(SIGNING_KEY.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                new JWTClaimsSet.Builder()
                        .subject(subject)
                        .issuer(ISSUER)
                        .audience("authenticated")
                        .issueTime(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                        .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                        .build());
        jwt.sign(new ECDSASigner(SIGNING_KEY));
        return jwt.serialize();
    }
}
