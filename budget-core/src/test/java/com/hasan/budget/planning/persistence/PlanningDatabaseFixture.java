package com.hasan.budget.planning.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real Postgres with every migration applied, for the planning module's integration tier.
 *
 * <p>Pinned to the image the compose file ships, because testing against a database you do not run
 * tests the wrong thing - and the things this tier is for are exactly the ones that differ between
 * databases: a trigger that refuses an update, a composite foreign key, a jsonb column.
 *
 * <p>Started in a static initialiser rather than by the {@code @Testcontainers} extension, for the
 * reason the cost-of-living fixture records: that extension stops the container when its own class
 * finishes while Spring keeps the context - and the pool pointing at a dead container - cached for
 * the next class, which then fails with connection refused for a reason that looks nothing like its
 * cause. This is a deliberate second copy of that pattern rather than a shared parent, because the
 * cost-of-living fixture belongs to another module.
 */
@SpringBootTest
abstract class PlanningDatabaseFixture {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine");

    static {
        POSTGRES.start();
    }
}
