package com.hasan.budget.costofliving.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real Postgres for the integration tier, with the migrations already applied.
 *
 * <p>Pinned to the same image the compose file ships. Testing against a database you do not run is
 * testing the wrong thing, and this is the layer where dialect differences - partial unique indexes,
 * identity columns, a nullable column sitting inside a unique index - are exactly what could break.
 *
 * <p>Testcontainers allocates its own random port rather than joining the shared local stack, so
 * this never collides with a development database on 5434.
 *
 * <p><strong>Started here rather than by the {@code @Testcontainers} extension, deliberately.</strong>
 * That extension stops a static container when its own test class finishes, while Spring keeps the
 * application context - and therefore the connection pool pointing at the dead container - cached
 * for the next class. The second integration class then fails with connection refused, thirty
 * seconds at a time, for a reason that looks nothing like its cause. Started once here, the
 * container outlives every class in the run and Ryuk removes it at the end.
 */
@SpringBootTest
abstract class CostOfLivingDatabaseFixture {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine");

    static {
        POSTGRES.start();
    }
}
