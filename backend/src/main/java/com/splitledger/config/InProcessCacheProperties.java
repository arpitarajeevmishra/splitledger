package com.splitledger.config;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The caches the in-process cache manager creates, and the bounds it creates them with.
 *
 * <p>Every cache is declared in configuration rather than conjured on first use. An
 * in-process cache shares its heap with the application, so a cache with no size limit
 * and no expiry is a memory leak that reports a healthy hit ratio while it grows; and a
 * declared set of names means a mistyped {@code @Cacheable} fails loudly instead of
 * quietly filling a second, unbounded cache.
 *
 * <p>Both bounds are mandatory for the same reason. There is no "unlimited" value and no
 * default to fall back on: a cache that cannot say how large it may get and how stale it
 * may serve is not configured yet.
 */
@ConfigurationProperties("app.cache")
public record InProcessCacheProperties(Map<String, CacheSpec> caches) {

    public InProcessCacheProperties {
        // LinkedHashMap, not Map.copyOf: iteration order is what the startup log reports,
        // and a stable order makes that log diffable between deployments.
        caches = (caches != null)
                ? Collections.unmodifiableMap(new LinkedHashMap<>(caches))
                : Map.of();
    }

    /**
     * One cache's bounds. {@code timeToLive} is time-to-live from the write, not from the
     * last read: the balance cache exists to absorb repeated reads of a value the ledger
     * may already have changed, so staleness has to be bounded by age, and an entry read
     * every few seconds must not become permanently fresh (LLD section 13).
     */
    public record CacheSpec(long maximumSize, Duration timeToLive) {

        public CacheSpec {
            if (maximumSize <= 0) {
                throw new IllegalArgumentException(
                        "maximum-size must be positive, so the cache cannot grow without "
                                + "bound inside the application's own heap; was " + maximumSize);
            }
            if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
                throw new IllegalArgumentException(
                        "time-to-live must be a positive duration, so no entry can be served "
                                + "indefinitely; was " + timeToLive);
            }
        }
    }
}
