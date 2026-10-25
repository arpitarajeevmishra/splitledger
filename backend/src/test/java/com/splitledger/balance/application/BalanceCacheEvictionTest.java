package com.splitledger.balance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.splitledger.ledger.application.LedgerChanged;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class BalanceCacheEvictionTest {

    private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Mock
    private GroupBalanceCache cache;

    @Test
    void evicts_the_group_whose_ledger_moved() {
        new BalanceCacheEviction(cache).onLedgerChanged(new LedgerChanged(GROUP));

        verify(cache).evict(GROUP);
    }

    @Test
    void listens_after_commit_and_not_before() throws Exception {
        TransactionalEventListener listener = BalanceCacheEviction.class
                .getMethod("onLedgerChanged", LedgerChanged.class)
                .getAnnotation(TransactionalEventListener.class);

        // Asserted by reflection because the phase is the entire guarantee and nothing
        // else here can show it. BEFORE_COMMIT would evict into a window in which a reader
        // repopulates the cache from data the writer is about to replace -- and that stale
        // entry then survives, with nothing left to evict it, until the TTL expires. A
        // one-word edit, invisible in every behavioural test, minutes of wrong balances.
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
