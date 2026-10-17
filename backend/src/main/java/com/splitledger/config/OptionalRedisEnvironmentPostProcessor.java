package com.splitledger.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * ADR-015 says a blank {@code REDIS_URL} selects the in-process cache. Nothing enforced
 * that. The Redis starter is on the classpath, so Spring Boot autoconfigured Lettuce
 * against localhost:6379 anyway, and the Redis health contributor then reported DOWN --
 * so {@code /actuator/health}, and with it the readiness probe Caddy depends on, called
 * the application unhealthy while it was serving every request correctly. A cache that
 * was documented as optional was in practice mandatory.
 *
 * <p>Whether an autoconfiguration is imported is decided before any bean exists, so this
 * cannot be a {@code @Conditional} on a {@code @Configuration} class; it has to happen
 * while the environment is still being prepared.
 */
public class OptionalRedisEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Named so it is recognisable in {@code /actuator/env}. */
    static final String PROPERTY_SOURCE_NAME = "splitledger-optional-redis";

    static final String REDIS_URL = "REDIS_URL";

    static final String EXCLUDE = "spring.autoconfigure.exclude";

    /**
     * Every autoconfiguration the Redis starter contributes. The reactive and repository
     * ones are inert in this application today, but excluding only some of them would
     * leave a half-wired Redis for the next person to trip over.
     */
    private static final List<String> REDIS_AUTOCONFIGURATION = List.of(
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.health."
                    + "DataRedisHealthContributorAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.health."
                    + "DataRedisReactiveHealthContributorAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.observation."
                    + "LettuceObservationAutoConfiguration");

    private final Log log;

    public OptionalRedisEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(OptionalRedisEnvironmentPostProcessor.class);
    }

    /**
     * After {@code ConfigDataEnvironmentPostProcessor}, because REDIS_URL usually arrives
     * from the {@code .env} file that application.yml imports rather than from the shell.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        if (StringUtils.hasText(environment.getProperty(REDIS_URL))) {
            return;   // a real Redis: leave autoconfiguration, and its health check, alone
        }

        Set<String> exclusions = new LinkedHashSet<>(Binder.get(environment)
                .bind(EXCLUDE, Bindable.listOf(String.class))
                .orElseGet(List::of));
        // Naming a class that is not on the classpath fails startup outright, so only
        // exclude what is actually present: dropping the starter must stay a one-line
        // change to the pom rather than a puzzle here.
        ClassLoader classLoader = classLoader(application);
        List<String> present = REDIS_AUTOCONFIGURATION.stream()
                .filter(name -> ClassUtils.isPresent(name, classLoader))
                .toList();
        if (present.isEmpty()) {
            return;
        }
        exclusions.addAll(present);

        environment.getPropertySources().addFirst(new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of(EXCLUDE, String.join(",", exclusions))));
        log.info("REDIS_URL is blank: excluding Redis autoconfiguration and its health "
                + "contributor, so health reflects the stores this instance actually uses "
                + "(ADR-015)");
    }

    private ClassLoader classLoader(SpringApplication application) {
        ClassLoader fromApplication = (application != null) ? application.getClassLoader() : null;
        return (fromApplication != null) ? fromApplication : getClass().getClassLoader();
    }
}
