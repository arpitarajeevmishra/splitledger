package com.splitledger.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.errors.RateLimitExceededException;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** A clock we advance by hand, so refill is asserted rather than waited for. */
    private static final class FakeClock implements TimeMeter {
        private long nanos;

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration by) {
            nanos += by.toNanos();
        }
    }

    private final FakeClock clock = new FakeClock();

    private RateLimiter limiter(long capacity, Duration per) {
        return new RateLimiter(new RateLimitProperties(
                Map.of("login", new RateLimitProperties.Limit(capacity, per))), clock, 1000);
    }

    @Test
    void allowsExactlyTheConfiguredAllowanceThenRefuses() {
        RateLimiter limiter = limiter(3, Duration.ofMinutes(1));

        limiter.check("login", "key");
        limiter.check("login", "key");
        limiter.check("login", "key");

        assertThatThrownBy(() -> limiter.check("login", "key"))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(ex -> ((RateLimitExceededException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void reportsHowLongToWait() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1));
        limiter.check("login", "key");

        assertThatThrownBy(() -> limiter.check("login", "key"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).retryAfter())
                        .isPositive()
                        .isLessThanOrEqualTo(Duration.ofMinutes(1)));
    }

    @Test
    void keysAreIndependentSoOneCallerCannotLockOutAnother() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1));

        limiter.check("login", "alice");
        // Alice is now exhausted; Bob must be unaffected. This is why the key includes the
        // email and not only the IP.
        limiter.check("login", "bob");

        assertThatThrownBy(() -> limiter.check("login", "alice"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void tokensReturnAsTheWindowPasses() {
        RateLimiter limiter = limiter(2, Duration.ofMinutes(1));
        limiter.check("login", "key");
        limiter.check("login", "key");
        assertThatThrownBy(() -> limiter.check("login", "key"))
                .isInstanceOf(RateLimitExceededException.class);

        // Greedy refill returns tokens smoothly, so half a window returns about half.
        clock.advance(Duration.ofSeconds(31));

        limiter.check("login", "key");
    }

    @Test
    void anUnconfiguredPolicyFailsLoudlyRatherThanAllowingEverything() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1));

        // A typo in a policy name must not silently disable the limit -- that is the worst
        // failure mode for a control whose job is to stop abuse.
        assertThatThrownBy(() -> limiter.check("lgoin", "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No rate limit configured");
    }
}
