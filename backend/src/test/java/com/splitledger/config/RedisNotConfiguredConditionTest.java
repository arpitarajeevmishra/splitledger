package com.splitledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Plain unit test: no Spring context, no database, no Docker. Runs in Surefire.
 *
 * <p>The readings asserted here are the same ones the environment post-processor applies
 * when it decides whether to exclude the Redis autoconfiguration -- the same variable, read
 * the same way. If those two ever drift apart, a deployment ends up with neither cache
 * manager, so the variable is named from one place in both tests.
 */
class RedisNotConfiguredConditionTest {

    @Test
    void anAbsentRedisUrlMeansNoRedis() {
        assertThat(RedisNotConfiguredCondition.redisIsConfigured(new MockEnvironment())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void whitespaceCountsAsBlankSoACarelesslyEditedEnvFileStillSelectsTheInProcessCache(
            String blank) {
        MockEnvironment environment =
                new MockEnvironment().withProperty(OptionalRedisEnvironmentPostProcessor.REDIS_URL, blank);

        assertThat(RedisNotConfiguredCondition.redisIsConfigured(environment)).isFalse();
    }

    @Test
    void aConfiguredRedisUrlMeansRedis() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OptionalRedisEnvironmentPostProcessor.REDIS_URL,
                        "rediss://cache.example.com:6380");

        assertThat(RedisNotConfiguredCondition.redisIsConfigured(environment)).isTrue();
    }
}
