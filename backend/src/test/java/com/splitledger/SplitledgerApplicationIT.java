package com.splitledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Named *IT so Failsafe runs it and Surefire does not: it starts real containers and
 * therefore needs a Docker daemon, which the development machine does not have.
 * This runs in CI only, via ./mvnw verify (ADR-012).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SplitledgerApplicationIT {

    @Test
    void contextLoadsAndFlywayMigrates() {
        // Starting the context runs V1__init.sql against a real PostgreSQL, so this
        // is also the migration's first real test.
    }
}
