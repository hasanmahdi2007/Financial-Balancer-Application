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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The gateway's half of authorization: a request with no usable token never reaches budget-core, and
 * the user id budget-core trusts is the one the token says and nothing else.
 *
 * <p>Runs against a key pair generated in the test, so nothing here touches Supabase or any network.
 * A suite that fails when DNS drops stops being believed within a week.
 */
class SupabaseAuthenticationFilterTest {

    private static final String ISSUER = "https://project.supabase.co/auth/v1";
    private static final String SOMEONE = "8a1f6b7c-0000-4000-8000-1234567890ab";

    private static ECKey supabaseKey;
    private static ECKey someoneElsesKey;

    private final SupabaseAuthenticationFilter filter =
            new SupabaseAuthenticationFilter(decoder(), new GatewayRateLimits(20, 5));

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final WebFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @BeforeAll
    static void generateKeys() throws Exception {
        supabaseKey = new ECKeyGenerator(Curve.P_256).keyID("supabase").generate();
        someoneElsesKey = new ECKeyGenerator(Curve.P_256).keyID("elsewhere").generate();
    }

    @Test
    @DisplayName("a request with no token is refused, and budget-core never sees it")
    void aRequestWithNoTokenNeverReachesTheDomain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/plan"));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).as("nothing may be forwarded without a caller").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .hasToString("application/problem+json");
    }

    @Test
    @DisplayName("a valid token is turned into the user id budget-core trusts")
    void aValidTokenBecomesTheInjectedUserId() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/plan").header(HttpHeaders.AUTHORIZATION, bearer(token(SOMEONE))));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(SupabaseAuthenticationFilter.USER_HEADER))
                .isEqualTo(SOMEONE);
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The one that matters most. budget-core trusts this header completely, so a client sending its
     * own must not have it forwarded - and it must be overwritten rather than rejected, since
     * refusing only the requests that carry one would tell an attacker they had found the right name.
     */
    @Test
    @DisplayName("a user id the caller sent themselves is replaced, never forwarded")
    void aForgedUserIdIsOverwrittenByTheOneTheTokenSays() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/plan")
                .header(HttpHeaders.AUTHORIZATION, bearer(token(SOMEONE)))
                .header(SupabaseAuthenticationFilter.USER_HEADER, "somebody-elses-account"));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get().getRequest().getHeaders().get(SupabaseAuthenticationFilter.USER_HEADER))
                .containsExactly(SOMEONE);
    }

    @Test
    @DisplayName("a token signed by a different Supabase project is refused")
    void aTokenFromAnotherProjectIsRefused() throws Exception {
        String minted = signedWith(someoneElsesKey, claims(SOMEONE, ISSUER, "authenticated", Instant.now().plus(1, ChronoUnit.HOURS)));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/plan").header(HttpHeaders.AUTHORIZATION, bearer(minted)));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an expired session is refused")
    void anExpiredTokenIsRefused() throws Exception {
        String expired = signedWith(
                supabaseKey, claims(SOMEONE, ISSUER, "authenticated", Instant.now().minus(1, ChronoUnit.HOURS)));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/plan").header(HttpHeaders.AUTHORIZATION, bearer(expired)));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** A correctly signed token from the wrong project, or issued for something else, is not ours. */
    @Test
    @DisplayName("a token for another issuer or another audience is refused")
    void aTokenForSomethingElseIsRefused() throws Exception {
        for (JWTClaimsSet wrong : List.of(
                claims(SOMEONE, "https://someone-else.supabase.co/auth/v1", "authenticated", Instant.now().plus(1, ChronoUnit.HOURS)),
                claims(SOMEONE, ISSUER, "some-other-app", Instant.now().plus(1, ChronoUnit.HOURS)))) {

            AtomicReference<ServerWebExchange> reached = new AtomicReference<>();
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/plan")
                    .header(HttpHeaders.AUTHORIZATION, bearer(signedWith(supabaseKey, wrong))));

            filter.filter(exchange, ex -> {
                        reached.set(ex);
                        return Mono.empty();
                    })
                    .block();

            assertThat(reached.get()).isNull();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("a signed-in caller is rate-limited as themselves rather than as an address")
    void aSignedInCallerIsTrackedByWhoTheyAre() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/plan").header(HttpHeaders.AUTHORIZATION, bearer(token(SOMEONE))));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get().<String>getAttribute(SupabaseAuthenticationFilter.TRACKING_ID))
                .isEqualTo("user:" + SOMEONE);
        assertThat(forwarded.get().<String>getAttribute(SupabaseAuthenticationFilter.CAPACITY)).isEqualTo("20");
        assertThat(forwarded.get().<String>getAttribute(SupabaseAuthenticationFilter.REFILL_RATE)).isEqualTo("5");
    }

    /**
     * Two things must get past without a token: the browser's preflight, which carries no credentials
     * by definition, and the health check, which Compose polls before anybody has signed in.
     */
    @Test
    @DisplayName("the preflight and the health check are not asked to sign in")
    void thePreflightAndHealthCheckAreLetThrough() {
        MockServerWebExchange preflight = MockServerWebExchange.from(
                MockServerHttpRequest.options("/api/v1/plan").header(HttpHeaders.ORIGIN, "http://localhost:5173"));
        filter.filter(preflight, chain).block();
        assertThat(forwarded.get()).isNotNull();

        forwarded.set(null);
        MockServerWebExchange health = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
        filter.filter(health, chain).block();
        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(SupabaseAuthenticationFilter.USER_HEADER))
                .as("a request nobody signed in for must carry no user either")
                .isNull();
    }

    // --- tokens, signed here so no test touches the network ----------------------------------------

    private static ReactiveJwtDecoder decoder() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSource(signed -> Flux.just(supabaseKey.toPublicJWK()))
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(SupabaseTokens.validator(ISSUER, "authenticated"));
        return decoder;
    }

    private static String token(String subject) throws Exception {
        return signedWith(supabaseKey, claims(subject, ISSUER, "authenticated", Instant.now().plus(1, ChronoUnit.HOURS)));
    }

    private static JWTClaimsSet claims(String subject, String issuer, String audience, Instant expiry) {
        return new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(expiry))
                .build();
    }

    private static String signedWith(ECKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(key.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
