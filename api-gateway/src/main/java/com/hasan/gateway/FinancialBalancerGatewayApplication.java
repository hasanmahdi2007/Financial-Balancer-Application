package com.hasan.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The only service in this system with a published port.
 *
 * <p>Adapted from the gateway in the author's {@code jobs-tracker-distributed-system}, which is
 * read-only: the rate limiting is carried across unchanged, and what changed is authentication.
 * That gateway sold API keys to other businesses and looked them up in its own database; this one
 * serves one person's own data, so the credential is a Supabase token the browser already holds, and
 * the API-key machinery - the key table, the client registration endpoint, the R2DBC connection it
 * needed - is gone rather than left dead beside it.
 *
 * <p>What the rest of the system depends on this service for is one thing: it turns a token into a
 * user id and injects it, and {@code budget-core} trusts that id because nothing else can reach it.
 */
@SpringBootApplication
public class FinancialBalancerGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinancialBalancerGatewayApplication.class, args);
    }
}
