package com.splitledger.expense.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitCalculator;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.ledger.domain.LedgerPosting;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=an-expense-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class ExpenseFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

    @Autowired
    private ExpenseService expenses;

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

    private UUID newGroup(Account owner, String name) throws Exception {
        return UUID.fromString(JsonPath.read(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private static String equalSplitBody(UUID payer, long amountPaise, List<UUID> participants) {
        StringBuilder people = new StringBuilder();
        for (UUID participant : participants) {
            if (!people.isEmpty()) {
                people.append(',');
            }
            people.append("{\"userId\":\"").append(participant).append("\"}");
        }
        return "{\"description\":\"Dinner\",\"amountPaise\":" + amountPaise
                + ",\"paidBy\":\"" + payer + "\",\"expenseDate\":\"2026-01-10\","
                + "\"splitType\":\"EQUAL\",\"participants\":[" + people + "]}";
    }

    private long netFor(UUID groupId, UUID userId) {
        return ledger.postingsFor(groupId).stream()
                .filter(posting -> posting.userId().equals(userId))
                .mapToLong(posting -> posting.amount().paise()).sum();
    }

    @Test
    void recordingAnExpenseWritesSplitsAndBalancedPostings() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplitBody(alice.id(), 90_000L,
                                List.of(alice.id(), bob.id(), carol.id()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountPaise").value(90_000))
                .andExpect(jsonPath("$.splits.length()").value(3))
                .andExpect(jsonPath("$.version").value(0));

        List<LedgerPosting> postings = ledger.postingsFor(groupId);
        assertThat(postings.stream().mapToLong(p -> p.amount().paise()).sum()).isZero();
        // LLD 5.1's worked example, read back from the database.
        assertThat(netFor(groupId, alice.id())).isEqualTo(60_000L);
        assertThat(netFor(groupId, bob.id())).isEqualTo(-30_000L);
        assertThat(netFor(groupId, carol.id())).isEqualTo(-30_000L);
    }

    @Test
    void aParticipantFromAnotherGroupIsRejectedWith422() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        UUID groupId = newGroup(alice, "Flat 402");
        newGroup(mallory, "Mallory's flat");

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplitBody(alice.id(), 90_000L,
                                List.of(alice.id(), mallory.id()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_MEMBER"));

        assertThat(ledger.postingsFor(groupId)).isEmpty();
    }

    @Test
    void aNonMemberCannotRecordAnExpenseAndGetsA404() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        UUID groupId = newGroup(alice, "Private");

        // 404 rather than 403: a stranger must not learn the group exists.
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + mallory.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplitBody(mallory.id(), 90_000L, List.of(mallory.id()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAmountOverTheCapIsRejectedBeforeItReachesTheDatabase() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice, "Flat 402");

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplitBody(alice.id(), 100_000_001L, List.of(alice.id()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theExpenseIsReadableBackWithItsSplits() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        String expenseId = JsonPath.read(mockMvc.perform(
                        post("/api/v1/groups/" + groupId + "/expenses")
                                .header("Authorization", "Bearer " + alice.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(equalSplitBody(alice.id(), 10_000L,
                                        List.of(alice.id(), bob.id()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits.length()").value(2));

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aRetryWithTheSameKeyCreatesOneExpenseAndReturnsTheFirstResponse() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        String key = UUID.randomUUID().toString();
        String body = equalSplitBody(alice.id(), 90_000L, List.of(alice.id(), bob.id()));

        String first = mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Same key, same body: the stored response, and still 201 rather than a 200 the
        // client never expected.
        String replayed = mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(replayed, "$.id"))
                .isEqualTo(JsonPath.read(first, "$.id"));
        mockMvc.perform(get("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.length()").value(1));
        // And only one ledger transaction, which is the part that would corrupt balances.
        assertThat(ledger.postingsFor(groupId)).hasSize(3);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRejectedWith422() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice, "Flat 402");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplitBody(alice.id(), 90_000L, List.of(alice.id()))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplitBody(alice.id(), 20_000L, List.of(alice.id()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void keysAreScopedPerUserSoTwoPeopleMayPickTheSameOne() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        String sharedKey = "the-same-key";

        for (Account who : List.of(alice, bob)) {
            mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                            .header("Authorization", "Bearer " + who.token())
                            .header("Idempotency-Key", sharedKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(equalSplitBody(who.id(), 10_000L,
                                    List.of(alice.id(), bob.id()))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void aFailedRequestReleasesItsKeyForImmediateRetry() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        UUID groupId = newGroup(alice, "Flat 402");
        String key = UUID.randomUUID().toString();

        // Fails validation: Mallory is not in this group.
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplitBody(alice.id(), 90_000L,
                                List.of(alice.id(), mallory.id()))))
                .andExpect(status().isUnprocessableEntity());

        // The same key must work immediately afterwards -- being locked out of your own
        // key for ten minutes because of a typo would be its own bug.
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplitBody(alice.id(), 90_000L, List.of(alice.id()))))
                .andExpect(status().isCreated());
    }

    @Test
    void withoutAKeyARetryCreatesASecondExpense() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice, "Flat 402");
        String body = equalSplitBody(alice.id(), 90_000L, List.of(alice.id()));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                            .header("Authorization", "Bearer " + alice.token())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }

        // Documenting the honest default rather than implying the endpoint is safe
        // without the header.
        mockMvc.perform(get("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * ADR-004 claims writes in one group serialise, and says the guarantee should be
     * provable rather than asserted. Twenty expenses are recorded at once through the
     * application layer; if the row lock did not hold, the postings would interleave and
     * the group's total would not come out at exactly zero.
     */
    @Test
    void concurrentExpensesInOneGroupSerialiseAndStillBalance() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice, "Busy flat");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        int writers = 20;
        List<ExpenseService.Participant> both = List.of(
                new ExpenseService.Participant(alice.id(), null),
                new ExpenseService.Participant(bob.id(), null));

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Object>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                tasks.add(() -> expenses.record(groupId, alice.id(), "Round", 10_001L,
                        alice.id(), LocalDate.of(2026, 1, 10), SplitType.EQUAL, both));
            }
            for (Future<Object> result : pool.invokeAll(tasks)) {
                result.get();
            }
        }

        List<LedgerPosting> postings = ledger.postingsFor(groupId);
        assertThat(postings.stream().mapToLong(p -> p.amount().paise()).sum()).isZero();

        // 10,001 split two ways is 5,001 and 5,000, and which of them gets the odd paisa
        // is decided by whose user id sorts lower -- random here, since the ids come from
        // registration. So the expectation is derived from the same deterministic
        // allocation rather than assumed: asserting that Alice gets 5,000 would have been
        // a test that passes about half the time.
        var share = SplitCalculator.equalSplit(10_001L, Set.of(alice.id(), bob.id()));
        assertThat(netFor(groupId, alice.id()))
                .isEqualTo(writers * (10_001L - share.get(alice.id())));
        assertThat(netFor(groupId, bob.id())).isEqualTo(-writers * share.get(bob.id()));
    }
}
