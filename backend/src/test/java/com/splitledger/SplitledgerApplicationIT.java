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
// app.jwt.secret has no default on purpose: production must fail fast rather than boot
// with a guessable signing key. Tests therefore supply one explicitly.
@SpringBootTest(properties = "app.jwt.secret=a-context-load-test-secret-long-enough-for-hs256")
class SplitledgerApplicationIT {

    @Test
    void contextLoadsAndFlywayMigrates() {
        // Starting the context runs V1__init.sql against a real PostgreSQL, so this
        // is also the migration's first real test.
    }
}
