package com.splitledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.money.Money;
import com.splitledger.ledger.domain.LedgerPosting;
import com.splitledger.ledger.domain.LedgerTransaction;
import com.splitledger.ledger.domain.TransactionType;
import com.splitledger.ledger.infra.LedgerPostingRepository;
import com.splitledger.ledger.infra.LedgerTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private LedgerTransactionRepository transactions;
    @Mock
    private LedgerPostingRepository postings;

    private LedgerService service;
    private UUID groupId;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    @BeforeEach
    void setUp() {
        groupId = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        carol = UUID.randomUUID();
        service = new LedgerService(transactions, postings, Clock.fixed(NOW, ZoneOffset.UTC));
        when(transactions.save(any())).thenAnswer(call -> call.getArgument(0));
        when(postings.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private List<LedgerService.Line> dinnerPaidByAlice() {
        // LLD 5.1's worked example: Alice paid 900, split equally three ways.
        return List.of(
                LedgerService.Line.of(alice, 90_000L),
                LedgerService.Line.of(alice, -30_000L),
                LedgerService.Line.of(bob, -30_000L),
                LedgerService.Line.of(carol, -30_000L));
    }

    @Test
    void postsABalancedTransaction() {
        UUID expenseId = UUID.randomUUID();

        LedgerTransaction posted = service.post(groupId, TransactionType.EXPENSE, expenseId,
                dinnerPaidByAlice());

        assertThat(posted.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(posted.sourceId()).isEqualTo(expenseId);
        assertThat(posted.createdAt()).isEqualTo(NOW);

        ArgumentCaptor<LedgerPosting> saved = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(postings, times(4)).save(saved.capture());
        assertThat(saved.getAllValues().stream()
                .mapToLong(posting -> posting.amount().paise()).sum()).isZero();
    }

    @Test
    void refusesPostingsThatDoNotSumToZero() {
        List<LedgerService.Line> unbalanced = List.of(
                LedgerService.Line.of(alice, 90_000L),
                LedgerService.Line.of(bob, -30_000L));

        // Not an ApiException: money appearing from nowhere is a bug in the calling
        // module, not something a user typed wrong.
        assertThatThrownBy(() -> service.post(groupId, TransactionType.EXPENSE,
                UUID.randomUUID(), unbalanced))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to zero");
        verify(transactions, never()).save(any());
    }

    @Test
    void refusesAPostingOfZero() {
        List<LedgerService.Line> withZero = List.of(
                LedgerService.Line.of(alice, 10_000L),
                LedgerService.Line.of(bob, -10_000L),
                LedgerService.Line.of(carol, 0L));

        assertThatThrownBy(() -> service.post(groupId, TransactionType.EXPENSE,
                UUID.randomUUID(), withZero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moves nothing");
    }

    @Test
    void refusesASingleSidedTransaction() {
        assertThatThrownBy(() -> service.post(groupId, TransactionType.EXPENSE,
                UUID.randomUUID(), List.of(LedgerService.Line.of(alice, 10_000L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two postings");
    }

    @Test
    void refusesToPostAReversalDirectly() {
        // A reversal has to mirror a real transaction, so it is derived, never supplied.
        assertThatThrownBy(() -> service.post(groupId, TransactionType.REVERSAL,
                UUID.randomUUID(), dinnerPaidByAlice()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reverse()");
    }

    @Test
    void aReversalMirrorsEveryPostingAndKeepsTheSourceId() {
        UUID expenseId = UUID.randomUUID();
        LedgerTransaction original =
                LedgerTransaction.of(groupId, TransactionType.EXPENSE, expenseId, NOW);
        when(transactions.findById(original.id())).thenReturn(Optional.of(original));
        when(postings.findAllByTxnId(original.id())).thenReturn(List.of(
                LedgerPosting.of(original.id(), groupId, alice, Money.ofPaise(60_000L)),
                LedgerPosting.of(original.id(), groupId, bob, Money.ofPaise(-30_000L)),
                LedgerPosting.of(original.id(), groupId, carol, Money.ofPaise(-30_000L))));

        LedgerTransaction reversal = service.reverse(original.id());

        assertThat(reversal.type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(reversal.reversesTxnId()).isEqualTo(original.id());
        // The source id is kept so both sides of a correction are findable from the thing
        // that caused them.
        assertThat(reversal.sourceId()).isEqualTo(expenseId);

        ArgumentCaptor<LedgerPosting> saved = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(postings, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(posting -> posting.amount().paise())
                .containsExactlyInAnyOrder(-60_000L, 30_000L, 30_000L);
    }

    @Test
    void originalAndReversalTogetherSumToZero() {
        UUID expenseId = UUID.randomUUID();
        LedgerTransaction original =
                LedgerTransaction.of(groupId, TransactionType.EXPENSE, expenseId, NOW);
        List<LedgerPosting> originalPostings = List.of(
                LedgerPosting.of(original.id(), groupId, alice, Money.ofPaise(60_000L)),
                LedgerPosting.of(original.id(), groupId, bob, Money.ofPaise(-60_000L)));
        when(transactions.findById(original.id())).thenReturn(Optional.of(original));
        when(postings.findAllByTxnId(original.id())).thenReturn(originalPostings);

        service.reverse(original.id());

        ArgumentCaptor<LedgerPosting> saved = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(postings, times(2)).save(saved.capture());
        long afterCorrection = originalPostings.stream()
                .mapToLong(posting -> posting.amount().paise()).sum()
                + saved.getAllValues().stream()
                .mapToLong(posting -> posting.amount().paise()).sum();
        // Reading the group's postings afterwards gives the same answer as if the
        // original had never happened -- while the history still shows that it did.
        assertThat(afterCorrection).isZero();
    }

    @Test
    void refusesToReverseTheSameTransactionTwice() {
        LedgerTransaction original =
                LedgerTransaction.of(groupId, TransactionType.EXPENSE, UUID.randomUUID(), NOW);
        when(transactions.findById(original.id())).thenReturn(Optional.of(original));
        when(transactions.existsByReversesTxnId(original.id())).thenReturn(true);

        // Twice would silently re-apply the original and double the balance.
        assertThatThrownBy(() -> service.reverse(original.id()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void refusesToReverseAReversal() {
        LedgerTransaction original =
                LedgerTransaction.of(groupId, TransactionType.EXPENSE, UUID.randomUUID(), NOW);
        LedgerTransaction reversal = LedgerTransaction.reversalOf(original, NOW);
        when(transactions.findById(reversal.id())).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> service.reverse(reversal.id()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void reversingSomethingThatDoesNotExistIsA404() {
        when(transactions.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reverse(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aDuplicateSourceBecomesAConflictRatherThanACrash() {
        doThrow(new DataIntegrityViolationException("uq_ledger_txn_source"))
                .when(transactions).flush();

        assertThatThrownBy(() -> service.post(groupId, TransactionType.EXPENSE,
                UUID.randomUUID(), dinnerPaidByAlice()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }
}
