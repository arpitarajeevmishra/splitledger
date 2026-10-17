package com.splitledger.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * "Is this instance running without a Redis?" -- the question ADR-015 turns on, asked
 * where a {@code @Configuration} class can ask it.
 *
 * <p>Keyed on {@link OptionalRedisEnvironmentPostProcessor#REDIS_URL}, the same constant
 * that class uses, and read with {@code hasText} rather than mere presence, because that
 * is exactly how it decides whether to exclude the Redis autoconfiguration. The two must
 * agree: if this condition were keyed on anything else -- a {@code spring.cache.type}
 * property, a profile, the presence of a {@code RedisConnectionFactory} -- a configuration
 * the post-processor reads as "no Redis" could be read here as "Redis", leaving a context
 * with no cache manager at all. One variable, one reading of it, in both places; sharing
 * the constant is what keeps that true as the two are edited.
 *
 * <p>The limitation is inherited too: a Redis supplied by Testcontainers through
 * {@code @ServiceConnection} arrives as a {@code ConnectionDetails} bean and never as
 * {@code REDIS_URL}, so neither this condition nor the post-processor can see it. The
 * integration suite therefore exercises the in-process branch, which is the branch that
 * is deployed (ADR-015).
 */
class RedisNotConfiguredCondition implements Condition {

    /** Exposed so the decision can be unit-tested without a {@code ConditionContext}. */
    static boolean redisIsConfigured(Environment environment) {
        return StringUtils.hasText(
                environment.getProperty(OptionalRedisEnvironmentPostProcessor.REDIS_URL));
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !redisIsConfigured(context.getEnvironment());
    }
}
