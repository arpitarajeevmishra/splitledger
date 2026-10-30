package com.splitledger.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.security.SecureTokens;
import com.splitledger.identity.domain.RefreshToken;
import com.splitledger.identity.infra.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final Duration GRACE = Duration.ofSeconds(30);

    @Mock
    private RefreshTokenRepository repository;
    @Mock
    private RefreshTokenChainRevoker chainRevoker;

    private RefreshTokenService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        // The clock is fixed and the tokens carry the timestamps: the grace window is
        // about how long ago a token was rotated, not about what time it is.
        service = new RefreshTokenService(repository, chainRevoker, TTL, GRACE,
                Clock.fixed(T0, ZoneOffset.UTC));
        userId = UUID.randomUUID();
    }

    @Test
    void storesOnlyTheHashNeverTheRawToken() {
        String raw = service.issue(userId);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(raw).isNotBlank();
        // The stored value must be the hash, and must not be the raw token.
        assertThat(saved.getValue().expiresAt()).isEqualTo(T0.plus(TTL));
        assertThat(SecureTokens.hash(raw)).hasSize(64);
    }

    @Test
    void rotationIssuesANewTokenAndRevokesTheOld() {
        String raw = "the-original-token";
        RefreshToken original = RefreshToken.issue(userId, SecureTokens.hash(raw),
                T0.plus(TTL));
        when(repository.findByTokenHash(SecureTokens.hash(raw)))
                .thenReturn(Optional.of(original));

        RefreshTokenService.Rotation rotation = service.rotate(raw);

        assertThat(rotation.userId()).isEqualTo(userId);
        assertThat(rotation.rawToken()).isNotEqualTo(raw);
        assertThat(original.isRevoked()).isTrue();
        assertThat(original.replacedBy()).isNotNull();
        verify(repository, times(2)).save(any(RefreshToken.class));   // replacement + old
    }

    @Test
    void reusingAnAlreadyRotatedTokenRevokesTheWholeChain() {
        String raw = "a-token-that-was-already-used";
        RefreshToken used = RefreshToken.issue(userId, SecureTokens.hash(raw),
                T0.plus(TTL));
        // Rotated long enough ago that the concurrency grace window cannot apply: a
        // replay this late is a leaked value, not the other tab's request arriving.
        used.rotateTo(UUID.randomUUID(), T0.minus(GRACE).minusSeconds(1));
        when(repository.findByTokenHash(SecureTokens.hash(raw)))
                .thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

        // Reuse means the value leaked, so every live token for this user must go.
        // Delegated deliberately: revoking here would be rolled back by the throw above,
        // which is the bug AuthFlowIT caught. This asserts the delegation; the
        // integration test asserts it actually persists.
        verify(chainRevoker).revokeChain(userId);
    }

    @Test
    void rejectsAnExpiredTokenWithoutRevokingAnythingElse() {
        String raw = "an-expired-token";
        RefreshToken expired = RefreshToken.issue(userId, SecureTokens.hash(raw),
                T0.minusSeconds(1));
        when(repository.findByTokenHash(SecureTokens.hash(raw)))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);

        verify(chainRevoker, never()).revokeChain(any());
    }

    @Test
    void rejectsAnUnknownToken() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("never-issued"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
    }

    /**
     * A token that was rotated away at {@code rotatedAt} in favour of {@code successor},
     * stubbed into the repository under its hash.
     */
    private RefreshToken rotatedAwayAt(String raw, Instant rotatedAt, RefreshToken successor) {
        RefreshToken token = RefreshToken.issue(userId, SecureTokens.hash(raw), T0.plus(TTL));
        token.rotateTo(successor.id(), rotatedAt);
        when(repository.findByTokenHash(SecureTokens.hash(raw))).thenReturn(Optional.of(token));
        return token;
    }

    private RefreshToken liveSuccessor() {
        return RefreshToken.issue(userId, SecureTokens.hash("the-successor"), T0.plus(TTL));
    }

    @Test
    void aConcurrentRefreshInsideTheGraceWindowIsServedWithoutANewToken() {
        RefreshToken successor = liveSuccessor();
        // Exactly on the edge of the window, which is inclusive.
        rotatedAwayAt("the-token-both-tabs-sent", T0.minus(GRACE), successor);
        when(repository.findById(successor.id())).thenReturn(Optional.of(successor));

        RefreshTokenService.Rotation rotation = service.rotate("the-token-both-tabs-sent");

        assertThat(rotation.userId()).isEqualTo(userId);
        // Null rather than a fresh token: the two tabs share one cookie, so the browser
        // keeps whichever Set-Cookie lands last. Issuing a second token here would
        // orphan the winner's and leave the user one refresh from being logged out.
        assertThat(rotation.rawToken()).isNull();
        verify(chainRevoker, never()).revokeChain(any());
        verify(repository, never()).save(any());
    }

    @Test
    void aReplayAfterTheGraceWindowRevokesTheChainEvenWithALiveSuccessor() {
        RefreshToken successor = liveSuccessor();
        rotatedAwayAt("a-token-replayed-too-late", T0.minus(GRACE).minusSeconds(1), successor);

        assertThatThrownBy(() -> service.rotate("a-token-replayed-too-late"))
                .isInstanceOf(ApiException.class);

        verify(chainRevoker).revokeChain(userId);
        // The age is checked before the successor is loaded, so a stale replay costs no query.
        verify(repository, never()).findById(any());
    }

    @Test
    void aTokenRevokedByLogoutIsNeverForgivenInsideTheWindow() {
        String raw = "a-token-killed-by-logout";
        RefreshToken loggedOut = RefreshToken.issue(userId, SecureTokens.hash(raw), T0.plus(TTL));
        loggedOut.revoke(T0);           // revoked without a rotation: replacedBy stays null
        when(repository.findByTokenHash(SecureTokens.hash(raw)))
                .thenReturn(Optional.of(loggedOut));

        assertThatThrownBy(() -> service.rotate(raw)).isInstanceOf(ApiException.class);

        // Forgiving this would make logout reversible for anyone still holding the cookie.
        verify(chainRevoker).revokeChain(userId);
        // And the successor is never looked up, because there is no successor id to look
        // up: asking the repository for null would throw rather than revoke the chain.
        verify(repository, never()).findById(any());
    }

    @Test
    void aReplayIsNotForgivenOnceTheSuccessorHasBeenRevoked() {
        RefreshToken successor = liveSuccessor();
        successor.revoke(T0);           // the chain was revoked after the rotation
        rotatedAwayAt("a-token-whose-chain-is-dead", T0.minusSeconds(1), successor);
        when(repository.findById(successor.id())).thenReturn(Optional.of(successor));

        assertThatThrownBy(() -> service.rotate("a-token-whose-chain-is-dead"))
                .isInstanceOf(ApiException.class);

        // A deliberate revocation outranks the window; otherwise it would resurrect a
        // session somebody ended on purpose.
        verify(chainRevoker).revokeChain(userId);
    }
}
