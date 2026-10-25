package com.splitledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    /**
     * A cache that has stopped working must cost latency and nothing else.
     *
     * <p>Without an error handler, the exception comes out of the {@code @Cacheable} method
     * itself, so a Redis that stops answering turns into a 500 on every balance in the
     * product -- the opposite of ADR-015's claim that the cache is optional. "Optional"
     * has to hold at runtime, not only at startup.
     */
    @Test
    void aCacheThatThrowsIsAMissRatherThanAFailure() {
        contextRunner
                .withPropertyValues(REDIS_URL)
                .withUserConfiguration(ExplodingCacheConfig.class)
                .run(context -> {
                    Counting counting = context.getBean(Counting.class);
                    java.util.concurrent.atomic.AtomicInteger calls =
                            context.getBean(java.util.concurrent.atomic.AtomicInteger.class);

                    assertThat(counting.value("k")).isEqualTo("computed:k");
                    assertThat(counting.value("k")).isEqualTo("computed:k");

                    // Twice, because nothing was ever cached. The point is not the number
                    // but that both calls returned an answer instead of a 500: a cache
                    // that has stopped working costs repeated work, never a failure.
                    assertThat(calls.get()).isEqualTo(2);
                });
    }

    @Test
    void anEvictionThatThrowsDoesNotFailTheWriteThatCausedIt() {
        contextRunner
                .withPropertyValues(REDIS_URL)
                .withUserConfiguration(ExplodingCacheConfig.class)
                .run(context -> {
                    // Swallowed deliberately. Failing here would roll back an expense that
                    // was correctly recorded in order to protect a cache entry, which is
                    // the wrong way round -- the entry goes stale until its TTL instead,
                    // and the handler logs it at error level for exactly that reason.
                    assertThatCode(() -> context.getBean(Counting.class).forget("k"))
                            .doesNotThrowAnyException();
                });
    }

    /** A cache manager whose every operation fails, standing in for a broker that stopped. */
    @Configuration(proxyBeanMethods = false)
    static class ExplodingCacheConfig {

        @Bean
        CacheManager cacheManager() {
            org.springframework.cache.Cache broken =
                    new org.springframework.cache.Cache() {
                        @Override
                        public String getName() {
                            return "bal";
                        }

                        @Override
                        public Object getNativeCache() {
                            return this;
                        }

                        @Override
                        public ValueWrapper get(Object key) {
                            throw new IllegalStateException("cache is down");
                        }

                        @Override
                        public <T> T get(Object key, Class<T> type) {
                            throw new IllegalStateException("cache is down");
                        }

                        @Override
                        public <T> T get(Object key, java.util.concurrent.Callable<T> loader) {
                            throw new IllegalStateException("cache is down");
                        }

                        @Override
                        public void put(Object key, Object value) {
                            throw new IllegalStateException("cache is down");
                        }

                        @Override
                        public void evict(Object key) {
                            throw new IllegalStateException("cache is down");
                        }

                        @Override
                        public void clear() {
                            throw new IllegalStateException("cache is down");
                        }
                    };
            return new org.springframework.cache.support.SimpleCacheManager() {
                @Override
                public org.springframework.cache.Cache getCache(String name) {
                    return broken;
                }
            };
        }

        @Bean
        java.util.concurrent.atomic.AtomicInteger calls() {
            return new java.util.concurrent.atomic.AtomicInteger();
        }

        @Bean
        Counting counting(java.util.concurrent.atomic.AtomicInteger calls) {
            return new Counting(calls);
        }
    }

    /** Stands in for the balance lookup: counts whether the real work actually ran. */
    static class Counting {

        // Its own bean, not a field read off this object: getBean returns the CGLIB proxy
        // and the body runs on the target, so a counter field here reads zero forever and
        // the test would pass against an implementation that never ran anything.
        private final java.util.concurrent.atomic.AtomicInteger calls;

        Counting(java.util.concurrent.atomic.AtomicInteger calls) {
            this.calls = calls;
        }

        @org.springframework.cache.annotation.Cacheable(cacheNames = "bal", key = "#key")
        String value(String key) {
            calls.incrementAndGet();
            return "computed:" + key;
        }

        @org.springframework.cache.annotation.CacheEvict(cacheNames = "bal", key = "#key")
        void forget(String key) {
            // The annotation is the behaviour.
        }
    }
}
