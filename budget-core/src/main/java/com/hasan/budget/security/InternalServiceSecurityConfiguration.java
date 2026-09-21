package com.hasan.budget.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * What {@code budget-core} does about authentication, which is deliberately almost nothing.
 *
 * <p><strong>Why this file exists at all.</strong> The OAuth2 resource-server starter is on the
 * classpath, and Spring Boot's rule is that security present with no configuration means lock
 * everything down: HTTP Basic, a generated password printed at startup, 401 on every route. That is
 * a sensible default for a service nobody has thought about yet, and it is wrong for this one. Left
 * alone it makes every endpoint any packet writes unreachable, including the health check the
 * Compose file needs, and the symptom - "the API returns 401" - looks like a bug in whichever
 * controller you happen to be testing.
 *
 * <p><strong>Why it permits rather than authenticates.</strong> This service publishes no port. The
 * gateway is the only thing that can reach it, the gateway validates the caller's token, and it
 * injects a user id this service trusts. Re-validating that token here would be checking the same
 * credential twice, in the one place where a second answer could only ever disagree with the first.
 * The controls that actually protect this service are two, and neither is a URL rule:
 *
 * <ol>
 *   <li><strong>Network isolation.</strong> {@code docker-compose.yml} publishes only the gateway's
 *       port. That is what makes trusting an injected header defensible rather than reckless - if
 *       this service were reachable directly, anyone could forge that header and read another
 *       person's bank data. The comment in that file saying so is load-bearing.
 *   <li><strong>Per-user query scoping.</strong> Every query for user data is keyed by the injected
 *       user id, so one account cannot read another's rows even from inside the network.
 * </ol>
 *
 * <p><strong>What packet P6 does with this.</strong> P6 builds the gateway and owns authorization.
 * If it decides defence in depth is worth it - validating the Supabase JWT here as well, or
 * requiring the injected header to be present so a misconfigured gateway fails loudly instead of
 * silently serving anonymous traffic - this bean is where that goes, and nothing else has to move.
 * Basic auth and form login are switched off explicitly rather than left to the default, because
 * neither has any meaning for a service whose callers are other services.
 */
@Configuration
@EnableWebSecurity
class InternalServiceSecurityConfiguration {

    @Bean
    SecurityFilterChain internalServiceFilterChain(HttpSecurity http) throws Exception {
        return http
                // No browser sessions and no forms, so there is no cookie for a forged cross-site
                // request to ride on. Enabling CSRF here would only break service-to-service POSTs.
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
