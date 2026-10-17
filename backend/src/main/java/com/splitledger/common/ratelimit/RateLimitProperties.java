package com.splitledger.common.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Named limits, configurable so they can be loosened in development and tightened in
 * production without a code change.
 *
 * @param limits policy name to its allowance, e.g. {@code login -> 5 per PT1M}
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Map<String, Limit> limits) {

    public RateLimitProperties {
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    public Limit require(String policy) {
        Limit limit = limits.get(policy);
        if (limit == null) {
            // A typo in a policy name would otherwise silently disable the limit, which is
            // the worst possible failure mode for a control that exists to stop abuse.
            throw new IllegalStateException(
                    "No rate limit configured for policy '" + policy + "'; known: " + limits.keySet());
        }
        return limit;
    }

    /** @param capacity requests allowed per {@code per} window */
    public record Limit(long capacity, Duration per) {
    }
}
