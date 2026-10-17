package com.splitledger.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * No Spring Boot application, no database and no Docker: {@code ApplicationContextRunner}
 * builds a context of exactly these configuration classes, which is the only way to assert
 * what a {@code @Conditional} actually decided.
 */
class CacheConfigurationTest {

    private static final String REDIS_URL = "REDIS_URL=rediss://cache.example.com:6380";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
            .withUserConfiguration(CacheConfiguration.class)
            .withPropertyValues(
                    "app.cache.caches.bal.maximum-size=10",
                    "app.cache.caches.bal.time-to-live=5m");

    @Test
    void aBlankRedisUrlCachesInProcess() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(CaffeineCacheManager.class);
        });
    }

    @Test
    void theInProcessCachesAreTheOnesConfiguredAndNothingElse() {
        contextRunner.run(context -> {
            CacheManager manager = context.getBean(CacheManager.class);

            assertThat(manager.getCacheNames()).containsExactly("bal");
            assertThat(manager.getCache("bal")).isNotNull();
            // Static mode: an undeclared name is not quietly created unbounded.
            assertThat(manager.getCache("balances-typo")).isNull();
        });
    }

    @Test
    void everyInProcessCacheIsBoundedBySizeAndByAge() {
        contextRunner.run(context -> {
            CaffeineCache cache = (CaffeineCache) context.getBean(CacheManager.class).getCache("bal");

            var policy = cache.getNativeCache().policy();
            assertThat(policy.eviction().orElseThrow().getMaximum()).isEqualTo(10);
            assertThat(policy.expireAfterWrite().orElseThrow().getExpiresAfter(TimeUnit.MINUTES))
                    .isEqualTo(5);
            // cache_hit_ratio (LLD 15) is nothing without this.
            assertThat(policy.isRecordingStats()).isTrue();
        });
    }

    @Test
    void cachesThatAreNotDeclaredAtAllFailStartupRatherThanCacheUnbounded() {
        new ApplicationContextRunner()
                .withUserConfiguration(CacheConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("app.cache.caches is empty");
                });
    }

    @Test
    void aConfiguredRedisUrlContributesNoCacheManagerOfItsOwn() {
        // Autoconfiguration is left out deliberately, so nothing in this context can supply
        // a CacheManager. The missing-bean failure is the assertion: with a REDIS_URL set,
        // this configuration contributes no manager and the Redis-backed one has to come
        // from autoconfiguration -- and @EnableCaching does not quietly proceed without one.
        new ApplicationContextRunner()
                .withUserConfiguration(CacheConfiguration.class)
                .withPropertyValues(REDIS_URL,
                        "app.cache.caches.bal.maximum-size=10",
                        "app.cache.caches.bal.time-to-live=5m")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .isInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasMessageContaining(CacheManager.class.getName());
                });
    }

    @Test
    void aConfiguredRedisUrlLeavesTheCacheManagerToAutoconfiguration() {
        contextRunner
                .withUserConfiguration(StubRedisConnection.class)
                .withPropertyValues(REDIS_URL)
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheManager.class);
                    assertThat(context.getBean(CacheManager.class))
                            .isInstanceOf(RedisCacheManager.class);
                });
    }

    @Test
    void cachingItselfIsEnabledOnEitherSideOfTheSwitch() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(CacheInterceptor.class));
        contextRunner.withUserConfiguration(StubRedisConnection.class)
                .withPropertyValues(REDIS_URL)
                .run(context -> assertThat(context).hasSingleBean(CacheInterceptor.class));
    }

    /**
     * Stands in for the connection factory Spring Boot autoconfigures from the Redis
     * starter. Nothing here connects: the assertion is about which cache manager is built,
     * not about talking to a Redis.
     */
    @Configuration(proxyBeanMethods = false)
    static class StubRedisConnection {

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }
    }
}
