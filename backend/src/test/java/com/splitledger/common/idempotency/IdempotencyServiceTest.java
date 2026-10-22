package com.splitledger.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");

    record Body(String description, long amountPaise) {
    }

    record Response(String id, long amountPaise) {
    }

    @Mock
    private IdempotencyClaims claims;

    private final ObjectMapper json = new ObjectMapper();
    private IdempotencyService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        service = new IdempotencyService(claims, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private IdempotencyRecord completed(Object request, Object response, int status) {
        IdempotencyRecord record = IdempotencyRecord.claim(userId, "key",
                com.splitledger.common.security.SecureTokens.hash(json.writeValueAsString(request)),
                NOW);
        record.complete(status, json.writeValueAsString(response));
        return record;
    }

    @Test
    void runsTheWorkWhenTheKeyIsFree() {
        Body request = new Body("Dinner", 90_000L);
        when(claims.claim(eq(userId), eq("key"), any(), any()))
                .thenReturn(Optional.of(IdempotencyRecord.claim(userId, "key", "h", NOW)));

        IdempotencyService.Result<Response> result = service.runOnce(userId, "key", request, 201,
                () -> new Response("e1", 90_000L), Response.class);

        assertThat(result.replayed()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.value().id()).isEqualTo("e1");
        verify(claims).complete(eq(userId), eq("key"), eq(201), any());
    }

    @Test
    void sameKeyAndSameBodyReturnsTheStoredResponseAndRunsNothing() {
        Body request = new Body("Dinner", 90_000L);
        when(claims.claim(eq(userId), eq("key"), any(), any())).thenReturn(Optional.empty());
        when(claims.find(userId, "key"))
                .thenReturn(Optional.of(completed(request, new Response("e1", 90_000L), 201)));

        AtomicInteger ran = new AtomicInteger();
        IdempotencyService.Result<Response> result = service.runOnce(userId, "key", request, 201,
                () -> {
                    ran.incrementAndGet();
                    return new Response("e2", 90_000L);
                }, Response.class);

        // The acceptance criterion: one expense, same response.
        assertThat(ran).hasValue(0);
        assertThat(result.replayed()).isTrue();
        assertThat(result.value().id()).isEqualTo("e1");
        // And the original status, so a retried creation still reads as 201.
        assertThat(result.httpStatus()).isEqualTo(201);
    }

    @Test
    void sameKeyWithADifferentBodyIsRefused() {
        when(claims.claim(eq(userId), eq("key"), any(), any())).thenReturn(Optional.empty());
        when(claims.find(userId, "key")).thenReturn(Optional.of(
                completed(new Body("Dinner", 90_000L), new Response("e1", 90_000L), 201)));

        // Answering with the first response would silently discard what they meant to send.
        assertThatThrownBy(() -> service.runOnce(userId, "key", new Body("Taxi", 20_000L), 201,
                () -> new Response("e2", 20_000L), Response.class))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void anIdenticalRequestStillInFlightGets409() {
        Body request = new Body("Dinner", 90_000L);
        when(claims.claim(eq(userId), eq("key"), any(), any())).thenReturn(Optional.empty());
        when(claims.find(userId, "key")).thenReturn(Optional.of(IdempotencyRecord.claim(userId,
                "key",
                com.splitledger.common.security.SecureTokens.hash(json.writeValueAsString(request)),
                NOW)));

        // Reachable only because the claim is committed before the work begins, which is
        // the whole point of finding C1.
        assertThatThrownBy(() -> service.runOnce(userId, "key", request, 201,
                () -> new Response("e2", 90_000L), Response.class))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    @Test
    void aFailedRequestReleasesItsKeySoTheClientCanRetryAtOnce() {
        when(claims.claim(eq(userId), eq("key"), any(), any()))
                .thenReturn(Optional.of(IdempotencyRecord.claim(userId, "key", "h", NOW)));

        assertThatThrownBy(() -> service.runOnce(userId, "key", new Body("Dinner", 90_000L), 201,
                () -> {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "nope");
                }, Response.class))
                .isInstanceOf(ApiException.class);

        // Otherwise a validation error would lock them out of their own key until the
        // purge job runs.
        verify(claims).release(userId, "key");
        verify(claims, never()).complete(any(), any(), anyInt(), any());
    }

    @Test
    void theHashIgnoresNothingThatMatters() {
        // Field order in the serialised body must not change the hash for the same record,
        // or an identical retry would look like a different request.
        Body request = new Body("Dinner", 90_000L);
        when(claims.claim(eq(userId), eq("key"), any(), any())).thenReturn(Optional.empty());
        when(claims.find(userId, "key"))
                .thenReturn(Optional.of(completed(request, new Response("e1", 90_000L), 201)));

        assertThat(service.runOnce(userId, "key", new Body("Dinner", 90_000L), 201,
                () -> new Response("e2", 0L), Response.class).value().id()).isEqualTo("e1");
    }

    @Test
    void aClaimThatVanishesBetweenTheAttemptAndTheLookupIsReportedAsInFlight() {
        when(claims.claim(eq(userId), eq("key"), any(), any())).thenReturn(Optional.empty());
        when(claims.find(userId, "key")).thenReturn(Optional.empty());

        // Only a concurrent failure releasing the key can do this; telling the client to
        // retry is the honest answer.
        assertThatThrownBy(() -> service.runOnce(userId, "key", new Body("Dinner", 1L), 201,
                () -> new Response("e1", 1L), Response.class))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }
}
