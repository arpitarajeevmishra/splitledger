package com.splitledger.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.identity.domain.RefreshToken;
import com.splitledger.identity.infra.RefreshTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenChainRevokerTest {

    @Mock
    private RefreshTokenRepository repository;

    @Test
    void revokesEveryTokenTheUserHas() {
        UUID userId = UUID.randomUUID();
        Instant future = Instant.now().plusSeconds(3600);
        RefreshToken first = RefreshToken.issue(userId, "a".repeat(64), future);
        RefreshToken second = RefreshToken.issue(userId, "b".repeat(64), future);
        when(repository.findAllByUserId(userId)).thenReturn(List.of(first, second));

        new RefreshTokenChainRevoker(repository).revokeChain(userId);

        assertThat(first.isRevoked()).isTrue();
        assertThat(second.isRevoked()).isTrue();
        verify(repository, times(2)).save(org.mockito.ArgumentMatchers.any(RefreshToken.class));
    }

    @Test
    void isHarmlessWhenThereIsNothingToRevoke() {
        UUID userId = UUID.randomUUID();
        when(repository.findAllByUserId(userId)).thenReturn(List.of());

        new RefreshTokenChainRevoker(repository).revokeChain(userId);

        verify(repository, times(0)).save(org.mockito.ArgumentMatchers.any(RefreshToken.class));
    }
}
