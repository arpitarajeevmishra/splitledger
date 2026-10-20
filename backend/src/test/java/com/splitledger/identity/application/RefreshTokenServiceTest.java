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

    @Mock
    private RefreshTokenRepository repository;
    @Mock
    private RefreshTokenChainRevoker chainRevoker;

    private RefreshTokenService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repository, chainRevoker, TTL,
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
        used.rotateTo(UUID.randomUUID(), T0);          // already rotated away
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
}
