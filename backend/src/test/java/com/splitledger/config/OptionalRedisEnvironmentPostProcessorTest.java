package com.splitledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.mock.env.MockEnvironment;

/** Plain unit test: no Spring context, no database, no Docker. Runs in Surefire. */
class OptionalRedisEnvironmentPostProcessorTest {

    private static final String HEALTH_CONTRIBUTOR =
            "DataRedisHealthContributorAutoConfiguration";

    private final OptionalRedisEnvironmentPostProcessor processor =
            new OptionalRedisEnvironmentPostProcessor(supplier -> new DeferredLog());

    @Test
    void aBlankRedisUrlExcludesRedisAutoconfigurationIncludingTheHealthContributor() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        // The health contributor is the one that matters: it is what reported DOWN and
        // made the readiness probe misrepresent a healthy instance.
        assertThat(environment.getProperty(OptionalRedisEnvironmentPostProcessor.EXCLUDE))
                .contains("DataRedisAutoConfiguration")
                .contains(HEALTH_CONTRIBUTOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void whitespaceCountsAsBlankSoACarelesslyEditedEnvFileStillSelectsNoRedis(String blank) {
        MockEnvironment environment =
                new MockEnvironment().withProperty(OptionalRedisEnvironmentPostProcessor.REDIS_URL, blank);

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(OptionalRedisEnvironmentPostProcessor.EXCLUDE))
                .contains(HEALTH_CONTRIBUTOR);
    }

    @Test
    void aConfiguredRedisUrlLeavesAutoconfigurationAndItsHealthCheckAlone() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OptionalRedisEnvironmentPostProcessor.REDIS_URL,
                        "rediss://cache.example.com:6380");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(OptionalRedisEnvironmentPostProcessor.EXCLUDE)).isNull();
        assertThat(environment.getPropertySources()
                .contains(OptionalRedisEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    void exclusionsConfiguredElsewhereSurvive() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OptionalRedisEnvironmentPostProcessor.EXCLUDE,
                        "com.example.SomeOtherAutoConfiguration");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(OptionalRedisEnvironmentPostProcessor.EXCLUDE))
                .contains("com.example.SomeOtherAutoConfiguration")
                .contains(HEALTH_CONTRIBUTOR);
    }
}
