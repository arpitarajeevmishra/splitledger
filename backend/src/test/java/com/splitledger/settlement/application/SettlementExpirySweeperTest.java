package com.splitledger.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.settlement.domain.Settlement;
import com.splitledger.settlement.domain.SettlementStatus;
import com.splitledger.settlement.infra.SettlementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The expiry job (SL-604, LLD 7.1, finding C17).
 *
 * <p>Written because the sweeper had no test at all. The state machine's own test proves
 * {@code expire()} is legal from INITIATED and PENDING; nothing proved the job ever
 * <em>asks</em> for both, and a sweeper that queried only PENDING would leave abandoned
 * settlements in every group's list forever while every other test still passed.
 */
@ExtendWith(MockitoExtension.class)
class SettlementExpirySweeperTest {

    private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

    @Mock
    private SettlementRepository settlements;

    private SettlementExpirySweeper sweeper() {
        return new SettlementExpirySweeper(settlements, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Settlement initiated(PaymentMethod method) {
        return Settlement.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                45_000L, method, NOW.minusSeconds(3600));
    }

    @Test
    void asksForBothUnfinishedStatusesAndNothingElse() {
        when(settlements.findAllByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of());

        sweeper().sweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(settlements).findAllByStatusInAndExpiresAtBefore(statuses.capture(), eq(NOW));

        // INITIATED matters as much as PENDING: a settlement abandoned before any order was
        // created would otherwise sit in the group's list forever (C17).
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(SettlementStatus.INITIATED, SettlementStatus.PENDING);
        // And nothing finished: expiring a CONFIRMED settlement would unsettle a paid debt.
        assertThat(statuses.getValue())
                .doesNotContain(SettlementStatus.CONFIRMED, SettlementStatus.FAILED,
                        SettlementStatus.CANCELLED, SettlementStatus.EXPIRED);
    }

    @Test
    void expiresWhatItFinds() {
        Settlement abandoned = initiated(PaymentMethod.RAZORPAY);
        when(settlements.findAllByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(abandoned));

        sweeper().sweep();

        assertThat(abandoned.status()).isEqualTo(SettlementStatus.EXPIRED);
        verify(settlements).save(abandoned);
    }

    @Test
    void anExpiredSettlementCanStillBeConfirmedLater() {
        // EXPIRED is not terminal, and this is why the sweeper expires rather than deletes:
        // a capture that arrives after the deadline means the money really moved.
        Settlement abandoned = initiated(PaymentMethod.RAZORPAY);
        when(settlements.findAllByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(abandoned));
        sweeper().sweep();

        abandoned.confirm(NOW.plusSeconds(60));

        assertThat(abandoned.status()).isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    void doesNothingAndSavesNothingWhenThereIsNothingDue() {
        when(settlements.findAllByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of());

        sweeper().sweep();

        verify(settlements, never()).save(any());
    }

    @Test
    void sweepsEveryMethodsDeadlineWithOneQuery() {
        // The deadlines differ per method (15 minutes for a checkout, 7 days for cash), but
        // they are already stored on the row as expires_at -- so the job compares dates and
        // never needs to know which method it is looking at.
        Settlement checkout = initiated(PaymentMethod.RAZORPAY);
        Settlement cash = initiated(PaymentMethod.CASH);
        when(settlements.findAllByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(checkout, cash));

        sweeper().sweep();

        assertThat(checkout.status()).isEqualTo(SettlementStatus.EXPIRED);
        assertThat(cash.status()).isEqualTo(SettlementStatus.EXPIRED);
        verify(settlements).findAllByStatusInAndExpiresAtBefore(any(), any());
    }
}
