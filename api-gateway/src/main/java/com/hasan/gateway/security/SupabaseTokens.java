package com.hasan.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * How a Supabase token is checked: against Supabase's own published keys, and against who it was
 * issued for.
 *
 * <p>The signature alone is not enough. A token signed by a different Supabase project is perfectly
 * valid, and accepting one would let anybody who can create a free project mint users of this app -
 * so the issuer is checked too. The audience is checked for the same reason, and the expiry because
 * a session that has ended has ended.
 *
 * <p>Keys are fetched from the JWKS endpoint and cached by the decoder, which is also what makes key
 * rotation a non-event: a new key id is fetched when a token first arrives signed by it.
 *
 * @param jwksUri where Supabase publishes the public keys it signs with
 * @param issuer the project those tokens must come from
 * @param audience what a signed-in user's token is issued for; Supabase uses "authenticated"
 */
@Configuration
@EnableConfigurationProperties({GatewayRateLimits.class})
@ConfigurationProperties(prefix = "supabase")
public class SupabaseTokens {

    private String jwksUri;
    private String issuer;
    private String audience = "authenticated";

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    @Bean
    ReactiveJwtDecoder supabaseJwtDecoder() {
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new IllegalStateException(
                    "SUPABASE_JWKS_URI is not set, so no token could ever be checked and every request "
                            + "would be refused. Copy .env across before starting the gateway.");
        }
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri)
                // Supabase signs with an elliptic-curve key by default and RSA on older projects.
                // Naming both is also what keeps an unsigned or symmetric "alg" from being accepted.
                .jwsAlgorithms(algorithms -> {
                    algorithms.add(SignatureAlgorithm.ES256);
                    algorithms.add(SignatureAlgorithm.RS256);
                })
                .build();
        decoder.setJwtValidator(validator(issuer, audience));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        JwtClaimValidator<List<String>> forThisApp = new JwtClaimValidator<>(
                JwtClaimNames.AUD, claimed -> claimed != null && claimed.contains(audience));
        return issuer == null || issuer.isBlank()
                ? new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(), forThisApp)
                : new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer), forThisApp);
    }
}
