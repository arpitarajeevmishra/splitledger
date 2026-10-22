package com.splitledger.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.ledger.domain.LedgerPosting;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The concurrency guarantees, demonstrated rather than asserted in prose.
 *
 * <p>ADR-004 claims writes within one group serialise and that two people cannot both win
 * an edit. A claim like that is worth very little until something tries to break it, so
 * each test here starts its threads on a barrier and lets them race the real database.
 *
 * <p>What is deliberately <b>not</b> asserted is that unrelated groups run in parallel.
 * Proving that needs timing, and a timing assertion on a CI runner is a flake waiting to
 * happen. What is asserted instead is that two groups writing at once both complete and
 * both stay correct -- which is what would fail if the lock were global or deadlocking.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-concurrency-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class ConcurrencyIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private BalanceService balances;

    @Autowired
    private LedgerService ledger;

    private record Account(String token, UUID id) {
    }

    private Account newUser(String label) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + label + "-" + System.nanoTime()
                                + "@example.com\",\"name\":\"" + label + "\","
                                + "\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Account(JsonPath.read(body, "$.accessToken"),
                UUID.fromString(JsonPath.read(body, "$.user.id")));
    }

    private UUID newGroup(Account owner) throws Exception {
        return UUID.fromString(JsonPath.read(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Busy flat\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private List<ExpenseService.Participant> both(Account one, Account two) {
        return List.of(new ExpenseService.Participant(one.id(), null),
                new ExpenseService.Participant(two.id(), null));
    }

    /**
     * Runs every task at once and returns what each one did -- the exception when it
     * threw, null when it succeeded. A barrier rather than just submitting to a pool,
     * so the threads are genuinely in flight together instead of trickling through.
     */
    private List<Throwable> raceAll(List<Callable<?>> work) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(work.size());
        List<Callable<Throwable>> guarded = new ArrayList<>();
        for (Callable<?> task : work) {
            guarded.add(() -> {
                startLine.await();
                try {
                    task.call();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
        }

        List<Throwable> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(work.size())) {
            for (Future<Throwable> result : pool.invokeAll(guarded)) {
                outcomes.add(result.get());
            }
        }
        return outcomes;
    }

    private static long netOf(List<LedgerPosting> postings, UUID userId) {
        return postings.stream()
                .filter(posting -> posting.userId().equals(userId))
                .mapToLong(posting -> posting.amount().paise()).sum();
    }

    @Test
    void twoSimultaneousEditsOfOneExpenseLeaveExactlyOneWinner() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        ExpenseService.Recorded original = expenses.record(groupId, alice.id(), "Dinner",
                90_000L, alice.id(), LocalDate.of(2026, 1, 10), SplitType.EQUAL,
                both(alice, bob));
        UUID expenseId = original.expense().id();

        // Both read version 0 and both try to replace it. Exactly one may win, or the
        // group ends up having paid for the same dinner twice.
        List<Throwable> outcomes = raceAll(List.of(
                () -> expenses.replace(expenseId, alice.id(), 0L, "Dinner, Alice's figure",
                        50_000L, alice.id(), LocalDate.of(2026, 1, 10), SplitType.EQUAL,
                        both(alice, bob)),
                () -> expenses.replace(expenseId, bob.id(), 0L, "Dinner, Bob's figure",
                        70_000L, alice.id(), LocalDate.of(2026, 1, 10), SplitType.EQUAL,
                        both(alice, bob))));

        assertThat(outcomes).filteredOn(failure -> failure == null).hasSize(1);
        Throwable loser = outcomes.stream().filter(f -> f != null).findFirst().orElseThrow();
        assertThat(rootApi(loser).code()).isEqualTo(ErrorCode.VERSION_CONFLICT);

        // One replacement, so one reversal and one new expense -- not two of each.
        assertThat(expenses.list(groupId, alice.id()))
                .filteredOn(expense -> expense.isActive()).hasSize(1);
        assertThat(ledger.postingsFor(groupId).stream()
                .mapToLong(posting -> posting.amount().paise()).sum()).isZero();
    }

    @Test
    void twoSimultaneousVoidsOfOneExpenseLeaveExactlyOneWinner() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        UUID expenseId = expenses.record(groupId, alice.id(), "Dinner", 90_000L, alice.id(),
                LocalDate.of(2026, 1, 10), SplitType.EQUAL, both(alice, bob)).expense().id();

        List<Throwable> outcomes = raceAll(List.of(
                () -> expenses.voidExpense(expenseId, alice.id(), 0L),
                () -> expenses.voidExpense(expenseId, bob.id(), 0L)));

        assertThat(outcomes).filteredOn(failure -> failure == null).hasSize(1);

        // Twice would reverse the reversal and quietly restore the original figures.
        List<LedgerPosting> postings = ledger.postingsFor(groupId);
        assertThat(postings).hasSize(6);
        assertThat(balances.of(groupId, alice.id()).net()).allMatch(MemberBalance::isSettled);
    }

    @Test
    void parallelExpensesInOneGroupSerialiseWithoutDrift() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        int writers = 16;
        List<Callable<?>> work = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            // An odd amount, so a lost update shows up as a net that is not a clean
            // multiple rather than silently balancing.
            work.add(() -> expenses.record(groupId, alice.id(), "Round", 10_001L, alice.id(),
                    LocalDate.of(2026, 1, 10), SplitType.EQUAL, both(alice, bob)));
        }

        assertThat(raceAll(work)).allSatisfy(failure -> assertThat(failure).isNull());

        List<LedgerPosting> postings = ledger.postingsFor(groupId);
        assertThat(postings.stream().mapToLong(p -> p.amount().paise()).sum()).isZero();
        assertThat(expenses.list(groupId, alice.id())).hasSize(writers);
        // Every expense allocated the same way, so each net is exactly a multiple of one
        // round's share.
        assertThat(netOf(postings, alice.id()) % writers).isZero();
        assertThat(netOf(postings, alice.id())).isEqualTo(-netOf(postings, bob.id()));
    }

    @Test
    void twoGroupsWritingAtOnceBothCompleteAndBothStayCorrect() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID first = newGroup(alice);
        UUID second = newGroup(alice);
        membership.joinOrRejoin(first, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(second, bob.id(), Role.MEMBER);

        List<Callable<?>> work = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            work.add(() -> expenses.record(first, alice.id(), "First", 20_000L, alice.id(),
                    LocalDate.of(2026, 1, 10), SplitType.EQUAL, both(alice, bob)));
            work.add(() -> expenses.record(second, alice.id(), "Second", 30_000L, alice.id(),
                    LocalDate.of(2026, 1, 10), SplitType.EQUAL, both(alice, bob)));
        }

        // The lock is on a group row, not a table: these must not deadlock on each other.
        assertThat(raceAll(work)).allSatisfy(failure -> assertThat(failure).isNull());

        assertThat(expenses.list(first, alice.id())).hasSize(8);
        assertThat(expenses.list(second, alice.id())).hasSize(8);
        assertThat(netOf(ledger.postingsFor(first), alice.id())).isEqualTo(8 * 10_000L);
        assertThat(netOf(ledger.postingsFor(second), alice.id())).isEqualTo(8 * 15_000L);
    }

    @Test
    void concurrentRetriesOfOneIdempotencyKeyCreateOneExpense() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        String key = UUID.randomUUID().toString();
        String body = "{\"description\":\"Dinner\",\"amountPaise\":90000,\"paidBy\":\""
                + alice.id() + "\",\"expenseDate\":\"2026-01-10\",\"splitType\":\"EQUAL\","
                + "\"participants\":[{\"userId\":\"" + alice.id() + "\"},{\"userId\":\""
                + bob.id() + "\"}]}";

        AtomicInteger created = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Callable<?>> work = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            work.add(() -> {
                int status = mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                                .header("Authorization", "Bearer " + alice.token())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
                if (status == 201) {
                    created.incrementAndGet();
                } else {
                    // 409 while the first is still in flight, or 201 replaying the stored
                    // response once it finished. Either is correct; a second expense is not.
                    refused.incrementAndGet();
                }
                return status;
            });
        }
        raceAll(work);

        // The guarantee that matters is this one, whatever mix of statuses came back.
        assertThat(expenses.list(groupId, alice.id())).hasSize(1);
        assertThat(created.get() + refused.get()).isEqualTo(6);
        assertThat(ledger.postingsFor(groupId)).hasSize(3);
    }

    private static ApiException rootApi(Throwable failure) {
        Throwable current = failure;
        while (current != null && !(current instanceof ApiException)) {
            current = current.getCause();
        }
        if (current == null) {
            throw new AssertionError("Expected an ApiException but got: " + failure, failure);
        }
        return (ApiException) current;
    }
}
