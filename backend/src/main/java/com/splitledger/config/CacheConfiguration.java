package com.splitledger.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.splitledger.config.InProcessCacheProperties.CacheSpec;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Caching, with or without a Redis to do it in.
 *
 * <p>ADR-015 says the deployed runtime drops the distributed cache for an in-process one
 * behind the same interface. Only the negative half of that existed: a blank
 * {@code REDIS_URL} stopped Spring Boot autoconfiguring Lettuce against localhost -- and
 * reporting the resulting failure as application ill health -- but left the application
 * with no cache manager at all, which makes "the cache is optional" true only in the
 * sense that there is nothing to opt out of. This is the other half.
 *
 * <p>{@code @EnableCaching} is deliberately unconditional. The cache abstraction is the
 * interface ADR-015 keeps stable; which implementation sits behind it is the thing that
 * varies, and a {@code @Cacheable} that silently stops caching when a URL changes would
 * be exactly the hidden coupling the ADR is trying to avoid.
 *
 * <p>A cache failure is a miss, never a request failure. That is what makes "the cache is
 * optional" true at runtime rather than only at startup: with Redis configured, a broker
 * that stops answering must cost latency and nothing else, and the default behaviour --
 * letting the exception out of the {@code @Cacheable} method -- would turn a cache outage
 * into an outage of every balance in the product.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration implements CachingConfigurer {

    private static final Logger errors = LoggerFactory.getLogger(CacheConfiguration.class);

    /**
     * Every cache operation degrades to doing without.
     *
     * <p>A read that fails is a miss, so the method body runs and the database answers. A
     * write that fails leaves the entry absent, which is the safe direction: the next read
     * recomputes. <b>An eviction that fails is the one that matters</b> -- swallowing it
     * leaves a stale balance in the cache -- and it is still swallowed, because failing the
     * write transaction over it would roll back an expense that was correctly recorded, to
     * protect a cache. It is logged at error level so that it is visible as what it is: a
     * number that will be wrong until its five-minute TTL expires.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException failure,
                    org.springframework.cache.Cache cache, Object key) {
                errors.warn("cache {}: read failed for {}, serving from the database",
                        cache.getName(), key, failure);
            }

            @Override
            public void handleCachePutError(RuntimeException failure,
                    org.springframework.cache.Cache cache, Object key, Object value) {
                errors.warn("cache {}: write failed for {}, leaving it uncached",
                        cache.getName(), key, failure);
            }

            @Override
            public void handleCacheEvictError(RuntimeException failure,
                    org.springframework.cache.Cache cache, Object key) {
                errors.error("cache {}: EVICTION FAILED for {} -- that entry is now stale "
                        + "until its TTL expires", cache.getName(), key, failure);
            }

            @Override
            public void handleCacheClearError(RuntimeException failure,
                    org.springframework.cache.Cache cache) {
                errors.error("cache {}: clear failed -- entries may be stale until their "
                        + "TTL expires", cache.getName(), failure);
            }
        };
    }

    /**
     * The in-process branch. When {@code REDIS_URL} carries a URL this class contributes
     * nothing, no {@code CacheManager} bean exists, and Spring Boot's own cache
     * autoconfiguration builds the Redis-backed manager on top of the connection factory
     * it already autoconfigures -- which is why there is no Redis code anywhere here.
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(RedisNotConfiguredCondition.class)
    @EnableConfigurationProperties(InProcessCacheProperties.class)
    static class InProcessCache {

        private static final Logger log = LoggerFactory.getLogger(InProcessCache.class);

        @Bean
        CaffeineCacheManager cacheManager(InProcessCacheProperties properties) {
            Map<String, CacheSpec> caches = properties.caches();
            if (caches.isEmpty()) {
                // Reachable only by deleting the caches from application.yml, and worth
                // failing on: an empty set would put the cache manager into its dynamic
                // mode, where the first @Cacheable creates an unbounded cache.
                throw new IllegalStateException(
                        "app.cache.caches is empty: with a blank REDIS_URL this application "
                                + "has no other cache, and an undeclared cache would be "
                                + "created unbounded on first use (ADR-015)");
            }

            CaffeineCacheManager manager = new CaffeineCacheManager();
            // Naming the caches switches the manager out of dynamic mode: getCache returns
            // null for anything undeclared, so a typo in a cache name is an error at the
            // first call rather than an unbounded cache nobody configured.
            manager.setCacheNames(caches.keySet());
            caches.forEach((name, spec) -> manager.registerCustomCache(name, build(spec)));

            log.info("REDIS_URL is blank: caching in-process (Caffeine) with {} (ADR-015)",
                    caches);
            return manager;
        }

        private static Cache<Object, Object> build(CacheSpec spec) {
            return Caffeine.newBuilder()
                    .maximumSize(spec.maximumSize())
                    .expireAfterWrite(spec.timeToLive())
                    // Caffeine collects nothing unless asked, and cache_hit_ratio is a
                    // metric LLD section 15 commits to; Micrometer binds these statistics
                    // to it. The cost is a few counters per cache.
                    .recordStats()
                    .build();
        }
    }
}
