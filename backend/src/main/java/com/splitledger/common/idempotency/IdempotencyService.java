package com.splitledger.common.idempotency;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.security.SecureTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes a POST safe to retry (ADR-007, LLD 8).
 *
 * <p>A client whose connection drops after the server committed has no way to tell that
 * from a request that never arrived. Without this, its retry creates a second expense and
 * the group's balances are quietly wrong. With it, the retry returns the first response.
 *
 * <p>The claim row is written and committed <em>before</em> the work runs, in its own
 * transaction, which is what makes a duplicate visible while the first request is still
 * in flight. Finding C1 is exactly the version of this that does not work: insert the
 * marker in the same transaction as the work and a concurrent duplicate blocks on the key
 * until commit, then fails on a key violation, so the advertised 409 can never happen.
 *
 * <p>Keys live in Postgres rather than Redis on purpose: this is a correctness mechanism,
 * and ADR-007 refuses to make the cache a correctness dependency.
 */
@Service
public class IdempotencyService {

    private final IdempotencyClaims claims;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    IdempotencyService(IdempotencyClaims claims, ObjectMapper json) {
        this(claims, json, Clock.systemUTC());
    }

    /** Visible for testing. */
    IdempotencyService(IdempotencyClaims claims, ObjectMapper json, Clock clock) {
        this.claims = claims;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param value the response to return
     * @param httpStatus the status it was first returned with -- a replay repeats it, so a
     *     retried creation still reads as 201 rather than turning into a 200 the client
     *     did not expect
     * @param replayed whether this came from a stored response rather than fresh work
     */
    public record Result<T>(T value, int httpStatus, boolean replayed) {
    }

    /**
     * Run {@code work} at most once per (user, key).
     *
     * @param request the request body, hashed to detect a key reused with different content
     */
    public <T> Result<T> runOnce(UUID userId, String key, Object request, int successStatus,
            Supplier<T> work, Class<T> responseType) {
        String requestHash = SecureTokens.hash(writeJson(request));

        Optional<IdempotencyRecord> claimed =
                claims.claim(userId, key, requestHash, Instant.now(clock));
        if (claimed.isEmpty()) {
            return replay(userId, key, requestHash, responseType);
        }

        T value;
        try {
            value = work.get();
        } catch (RuntimeException failure) {
            // Leaving the claim would lock the client out of its own key until the purge
            // job runs. A request that failed cleanly should be retryable at once.
            claims.release(userId, key);
            throw failure;
        }
        claims.complete(userId, key, successStatus, writeJson(value));
        return new Result<>(value, successStatus, false);
    }

    private <T> Result<T> replay(UUID userId, String key, String requestHash,
            Class<T> responseType) {
        IdempotencyRecord existing = claims.find(userId, key)
                // Claimed a moment ago and already gone: the only way is a concurrent
                // failure releasing it. Telling the client to retry is honest.
                .orElseThrow(() -> new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                        "That request is still being processed; retry shortly"));

        if (!existing.matches(requestHash)) {
            // Answering with the first request's response would silently discard whatever
            // they meant to send this time.
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "That idempotency key was already used for a different request");
        }
        if (!existing.isCompleted()) {
            // Reachable only because the claim is committed before the work starts --
            // which is the whole point of finding C1.
            throw new ApiException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "An identical request is still being processed");
        }
        return new Result<>(readJson(existing.responseBody(), responseType),
                existing.responseStatus(), true);
    }

    private String writeJson(Object value) {
        // Jackson 3 (tools.jackson), not Jackson 2 -- Boot 4 ships the former, and the
        // two are drop-in similar enough to compile and then fail to wire (D-27).
        return json.writeValueAsString(value);
    }

    private <T> T readJson(String body, Class<T> type) {
        return json.readValue(body, type);
    }
}
