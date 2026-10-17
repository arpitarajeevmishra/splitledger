package com.splitledger.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.splitledger.common.errors.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * In-process token-bucket rate limiting (LLD section 12).
 *
 * <p>In process, not in Redis, for the same reason the cache is (ADR-015): there is one
 * application instance, and Redis is optional. The trade-off is explicit -- with two
 * instances each would allow the full quota, so this moves to a shared store at the same
 * time the cache does, behind this same interface.
 *
 * <p>Buckets live in a bounded Caffeine cache. That bound matters: the key includes a
 * caller-supplied email, so an unbounded map would be a memory-exhaustion vector handed
 * to anyone who can reach the login endpoint. Eviction only forgives a limit early, which
 * is the safe direction to fail.
 */
@Component
public class RateLimiter {

    private final RateLimitProperties properties;
    private final TimeMeter timeMeter;
    private final Cache<String, Bucket> buckets;

    RateLimiter(RateLimitProperties properties) {
        this(properties, TimeMeter.SYSTEM_MILLISECONDS, 100_000);
    }

    /** Visible for testing: a controllable clock makes refill assertions deterministic. */
    RateLimiter(RateLimitProperties properties, TimeMeter timeMeter, long maxBuckets) {
        this.properties = properties;
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maxBuckets)
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build();
    }

    /**
     * @throws RateLimitExceededException when the caller has no tokens left, carrying the
     *     wait until one is available
     */
    public void check(String policy, String key) {
        RateLimitProperties.Limit limit = properties.require(policy);
        Bucket bucket = buckets.get(policy + ':' + key, k -> newBucket(limit));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            Duration wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
            throw new RateLimitExceededException(wait,
                    "Too many requests; retry in " + Math.max(1, wait.toSeconds()) + "s");
        }
    }

    private Bucket newBucket(RateLimitProperties.Limit limit) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        // Greedy refill: tokens return smoothly across the window rather than
                        // all at once, so a blocked caller is not handed a full new quota the
                        // instant the window rolls over.
                        .refillGreedy(limit.capacity(), limit.per())
                        .build())
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
