package com.splitledger.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo group at start-up, when asked (SL-705).
 *
 * <p><b>The password's presence is the switch.</b> There is no separate boolean, because a
 * boolean and a password are two settings that can disagree -- enabled with no password is
 * a start-up failure, and a password with the flag off is somebody wondering why nothing
 * happened. One setting cannot contradict itself.
 *
 * <p>{@code @ConditionalOnExpression} rather than {@code @ConditionalOnProperty}: an
 * {@code DEMO_SEED_PASSWORD=} line with nothing after it resolves to the empty string,
 * which the property form treats as present (D-115, and again in SL-701). Seeding a
 * database because an environment variable was left blank is not a mistake worth making
 * twice.
 */
@Component
@ConditionalOnExpression("'${app.demo.seed-password:}'.trim() != ''")
class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    private final DemoSeed seed;
    private final String password;

    DemoSeedRunner(DemoSeed seed, @Value("${app.demo.seed-password:}") String password) {
        this.seed = seed;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        DemoSeed.Seeded seeded = seed.seed(password);
        // The group id, because the next thing anybody does is open it -- in a browser for
        // the tour, or as a k6 parameter for the load run.
        log.info("Demo group {} is {}", seeded.groupId(),
                seeded.created() ? "seeded" : "already present");
    }
}
