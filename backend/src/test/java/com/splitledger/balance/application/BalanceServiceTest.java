package com.splitledger.balance.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.balance.domain.BalanceSnapshot;
import com.splitledger.balance.infra.BalanceQueries;
import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.group.application.GroupAccessGuard;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The thing caching must not break (SL-703).
 *
 * <p>Balances are now served from a cache keyed on the group alone, which means the only
 * thing standing between a stranger and a group's finances is that the membership check
 * runs <em>before</em> the cache is consulted, on a different bean. Put {@code @Cacheable}
 * on these methods instead and a hit returns through the proxy without the body -- and the
 * check is in the body. These tests are what stops that being refactored back in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceServiceTest {

    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000a9");

    @Mock
    private BalanceQueries queries;
    @Mock
    private GroupBalanceCache cache;
    @Mock
    private GroupAccessGuard guard;

    private BalanceService service() {
        return new BalanceService(queries, cache, guard);
    }

    private void refuseStranger() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND, "No such group"))
                .when(guard).requireMember(GROUP, STRANGER);
    }

    @Test
    void a_stranger_is_refused_before_the_cache_is_touched() {
        refuseStranger();

        assertThatThrownBy(() -> service().of(GROUP, STRANGER)).isInstanceOf(ApiException.class);

        // Not merely "throws": the cache must not even be asked. A warmed entry plus an
        // authorisation check that ran afterwards would still be a bypass, and would still
        // pass a test that only asserted the exception.
        verify(cache, never()).of(any());
    }

    @Test
    void the_same_refusal_applies_to_the_net_figures() {
        refuseStranger();

        assertThatThrownBy(() -> service().netOnly(GROUP, STRANGER))
                .isInstanceOf(ApiException.class);

        verify(cache, never()).of(any());
    }

    @Test
    void and_to_the_simplified_plan() {
        refuseStranger();

        assertThatThrownBy(() -> service().simplified(GROUP, STRANGER))
                .isInstanceOf(ApiException.class);

        verify(cache, never()).of(any());
    }

    @Test
    void a_member_gets_one_snapshot_rather_than_two_lookups() {
        when(cache.of(GROUP)).thenReturn(new BalanceSnapshot(List.of(), List.of(), List.of()));

        service().of(GROUP, STRANGER);

        // Two lookups would let an eviction land between them and pair a net balance from
        // one snapshot with a pairwise view from another -- two numbers on one screen that
        // disagree, which this product has shipped once already.
        verify(cache).of(GROUP);
    }
}
