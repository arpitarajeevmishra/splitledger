package com.splitledger.common.errors;

import java.time.Duration;

/**
 * Carries how long the caller should wait, so the handler can set {@code Retry-After}.
 * A 429 without that header tells a client it was throttled but not when to try again,
 * which usually means it retries immediately and is throttled again.
 */
public class RateLimitExceededException extends ApiException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter, String detail) {
        super(ErrorCode.RATE_LIMITED, detail);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
