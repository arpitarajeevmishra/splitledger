package com.splitledger.expense.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.expense.infra.ExpenseRepository;
import com.splitledger.expense.infra.ExpenseSplitRepository;
import com.splitledger.group.application.GroupAccessGuard;
import com.splitledger.group.application.GroupLock;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.ledger.domain.TransactionType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpenseServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");
    private static final LocalDate ON = LocalDate.of(2026, 1, 10);

    @Mock
    private ExpenseRepository expenses;
    @Mock
    private ExpenseSplitRepository splits;
    @Mock
    private GroupMemberRepository members;
    @Mock
    private GroupAccessGuard guard;
    @Mock
    private GroupLock groupLock;
    @Mock
    private LedgerService ledger;

    private ExpenseService service;
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
        service = new ExpenseService(expenses, splits, members, guard, groupLock, ledger,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(groupLock.acquire(groupId))
                .thenReturn(ExpenseGroup.create("Flat 402", "INR", alice, NOW));
        when(members.findAllByIdGroupIdAndLeftAtIsNull(groupId)).thenReturn(List.of(
                GroupMember.join(groupId, alice, Role.ADMIN, NOW),
                GroupMember.join(groupId, bob, Role.MEMBER, NOW),
                GroupMember.join(groupId, carol, Role.MEMBER, NOW)));
        when(expenses.save(any())).thenAnswer(call -> call.getArgument(0));
        when(splits.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private List<ExpenseService.Participant> everyone() {
        return List.of(new ExpenseService.Participant(alice, null),
                new ExpenseService.Participant(bob, null),
                new ExpenseService.Participant(carol, null));
    }

    private ExpenseService.Recorded dinner() {
        return service.record(groupId, alice, "Dinner", 90_000L, alice, ON,
                SplitType.EQUAL, everyone());
    }

    @SuppressWarnings("unchecked")
    private List<LedgerService.Line> postedLines() {
        ArgumentCaptor<List<LedgerService.Line>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledger).post(eq(groupId), eq(TransactionType.EXPENSE), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void writesTheExpenseItsSplitsAndBalancedPostings() {
        ExpenseService.Recorded recorded = dinner();

        assertThat(recorded.expense().amountPaise()).isEqualTo(90_000L);
        assertThat(recorded.splits()).hasSize(3);
        assertThat(recorded.splits()).allSatisfy(split ->
                assertThat(split.owedPaise()).isEqualTo(30_000L));

        // The acceptance criterion: postings sum to zero.
        assertThat(postedLines().stream()
                .mapToLong(line -> line.amount().paise()).sum()).isZero();
    }

    @Test
    void thePayerIsCreditedTheWholeAmountAndDebitedOnlyTheirShare() {
        dinner();

        long aliceNet = postedLines().stream()
                .filter(line -> line.userId().equals(alice))
                .mapToLong(line -> line.amount().paise()).sum();
        // Laid out 900, owes 300, so the group owes her 600 (LLD 5.1).
        assertThat(aliceNet).isEqualTo(60_000L);
    }

    @Test
    void theGroupIsLockedBeforeMembershipIsRead() {
        dinner();

        // Validating first would leave a window in which someone leaves the group between
        // the check and the write.
        InOrder order = inOrder(groupLock, members, ledger);
        order.verify(groupLock).acquire(groupId);
        order.verify(members).findAllByIdGroupIdAndLeftAtIsNull(groupId);
        order.verify(ledger).post(any(), any(), any(), any());
    }

    @Test
    void aParticipantFromAnotherGroupIsRejected() {
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.record(groupId, alice, "Dinner", 90_000L, alice, ON,
                SplitType.EQUAL, List.of(new ExpenseService.Participant(alice, null),
                        new ExpenseService.Participant(stranger, null))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PARTICIPANT_NOT_MEMBER);
        verify(ledger, never()).post(any(), any(), any(), any());
    }

    @Test
    void aPayerWhoIsNotAMemberIsRejected() {
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.record(groupId, alice, "Dinner", 90_000L, stranger, ON,
                SplitType.EQUAL, everyone()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PARTICIPANT_NOT_MEMBER);
        verify(expenses, never()).save(any());
    }

    @Test
    void aDepartedMemberCannotBeAParticipant() {
        GroupMember departed = GroupMember.join(groupId, carol, Role.MEMBER, NOW);
        departed.leave(NOW);
        when(members.findAllByIdGroupIdAndLeftAtIsNull(groupId)).thenReturn(List.of(
                GroupMember.join(groupId, alice, Role.ADMIN, NOW),
                GroupMember.join(groupId, bob, Role.MEMBER, NOW)));

        assertThatThrownBy(() -> service.record(groupId, alice, "Dinner", 90_000L, alice, ON,
                SplitType.EQUAL, everyone()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.PARTICIPANT_NOT_MEMBER);
    }

    @Test
    void theSameParticipantTwiceIsRejectedRatherThanSilentlyDeduplicated() {
        // Left alone, the duplicate overwrites and the expense is split among fewer
        // people than the request listed.
        assertThatThrownBy(() -> service.record(groupId, alice, "Dinner", 90_000L, alice, ON,
                SplitType.EQUAL, List.of(new ExpenseService.Participant(alice, null),
                        new ExpenseService.Participant(bob, null),
                        new ExpenseService.Participant(bob, null))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void exactSplitsThatDoNotAddUpAreRejected() {
        assertThatThrownBy(() -> service.record(groupId, alice, "Dinner", 90_000L, alice, ON,
                SplitType.EXACT, List.of(new ExpenseService.Participant(alice, 50_000L),
                        new ExpenseService.Participant(bob, 30_000L))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.SPLIT_SUM_MISMATCH);
        verify(ledger, never()).post(any(), any(), any(), any());
    }

    @Test
    void aMissingValueForAValueBasedSplitIsRejected() {
        assertThatThrownBy(() -> service.record(groupId, alice, "Dinner", 90_000L, alice, ON,
                SplitType.SHARES, List.of(new ExpenseService.Participant(alice, 1L),
                        new ExpenseService.Participant(bob, null))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void theInputValueIsKeptBesideTheComputedShare() {
        service.record(groupId, alice, "Dinner", 60_000L, alice, ON, SplitType.SHARES,
                List.of(new ExpenseService.Participant(alice, 1L),
                        new ExpenseService.Participant(bob, 2L),
                        new ExpenseService.Participant(carol, 3L)));

        ArgumentCaptor<com.splitledger.expense.domain.ExpenseSplit> saved =
                ArgumentCaptor.forClass(com.splitledger.expense.domain.ExpenseSplit.class);
        verify(splits, org.mockito.Mockito.times(3)).save(saved.capture());
        // Without the input value a 1:2:3 split is indistinguishable from someone typing
        // 10000/20000/30000 by hand, and a correction cannot reproduce the intent.
        assertThat(saved.getAllValues()).extracting(
                com.splitledger.expense.domain.ExpenseSplit::inputValue)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void aZeroShareProducesASplitRowButNoPosting() {
        // One paisa among three: two owe nothing. The split row records that they took
        // part; a zero posting would be refused by the ledger as moving nothing.
        service.record(groupId, alice, "Sweet", 1L, alice, ON, SplitType.EQUAL, everyone());

        verify(splits, org.mockito.Mockito.times(3)).save(any());
        assertThat(postedLines()).hasSize(2);
        assertThat(postedLines().stream()
                .mapToLong(line -> line.amount().paise()).sum()).isZero();
    }

    @Test
    void aNonMemberCannotRecordAnExpenseAndNeverReachesTheLock() {
        when(guard.requireMember(groupId, bob))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND, "No such group"));

        assertThatThrownBy(() -> service.record(groupId, bob, "Dinner", 90_000L, alice, ON,
                SplitType.EQUAL, everyone()))
                .isInstanceOf(ApiException.class);
        verify(groupLock, never()).acquire(any());
    }

    @Test
    void aGroupCannotHaveANonInrCurrencyInTheFirstPlace() {
        // Written as a currency test for ExpenseService, which is where the check seemed
        // to belong -- until the test could not build its own input. ExpenseGroup refuses
        // it at creation and the schema has CHECK (currency = 'INR'), so the service has
        // nothing left to guard against.
        assertThatThrownBy(() -> ExpenseGroup.create("Euro trip", "EUR", alice, NOW))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CURRENCY_UNSUPPORTED);
    }
}
