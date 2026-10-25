package com.splitledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.ledger.domain.LedgerPosting;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The cash and UPI-intent lifecycle, and the two things it must get right: confirming
 * twice posts once, and voiding a settled expense leaves a coherent overpaid state
 * (finding C9).
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-lifecycle-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000",
        // The sweeper must not fire mid-test and expire what a test is still using.
        "app.settlements.expiry-cron=0 0 5 31 2 ?"
})
class SettlementLifecycleIT {

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

    private record Pair(Account alice, Account bob, UUID groupId, String settlementId) {
    }

    /** Alice pays 200 for both, so Bob owes 100; Bob then starts settling it. */
    private Pair owedAndSettling(long expensePaise, long settlePaise) throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = UUID.fromString(JsonPath.read(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 402\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id"));
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        expenses.record(groupId, alice.id(), "Dinner", expensePaise, alice.id(),
                LocalDate.of(2026, 1, 10), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(alice.id(), null),
                        new ExpenseService.Participant(bob.id(), null)));

        String settlementId = JsonPath.read(mockMvc.perform(
                        post("/api/v1/groups/" + groupId + "/settlements")
                                .header("Authorization", "Bearer " + bob.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"toUser\":\"" + alice.id() + "\",\"amountPaise\":"
                                        + settlePaise + ",\"method\":\"CASH\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        return new Pair(alice, bob, groupId, settlementId);
    }

    private ResultActions act(Account who, String settlementId, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/settlements/" + settlementId + "/" + action)
                .header("Authorization", "Bearer " + who.token()));
    }

    private long netOf(UUID groupId, Account who, Account reader) {
        return balances.of(groupId, reader.id()).net().stream()
                .filter(balance -> balance.userId().equals(who.id()))
                .mapToLong(balance -> balance.net().paise())
                .findFirst().orElse(0L);
    }

    @Test
    void theHappyPathClearsTheDebt() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);

        act(p.bob(), p.settlementId(), "mark-paid")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        act(p.alice(), p.settlementId(), "confirm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(netOf(p.groupId(), p.alice(), p.alice())).isZero();
        assertThat(netOf(p.groupId(), p.bob(), p.alice())).isZero();
        assertThat(balances.of(p.groupId(), p.alice().id()).pairwise()).isEmpty();
    }

    @Test
    void confirmingTwiceSucceedsBothTimesAndPostsOnce() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);
        act(p.bob(), p.settlementId(), "mark-paid").andExpect(status().isOk());

        act(p.alice(), p.settlementId(), "confirm").andExpect(status().isOk());
        // A second tap, or a retried request. Same event, so it succeeds and posts nothing.
        act(p.alice(), p.settlementId(), "confirm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        List<LedgerPosting> postings = ledger.postingsFor(p.groupId());
        // Three from the expense, two from the one settlement. A second posting would
        // have doubled the payment and left Alice looking owed money she received.
        assertThat(postings).hasSize(5);
        assertThat(netOf(p.groupId(), p.alice(), p.alice())).isZero();
    }

    @Test
    void onlyTheReceiverCanConfirm() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);
        act(p.bob(), p.settlementId(), "mark-paid").andExpect(status().isOk());

        // Otherwise anyone could settle their own debts by paying nothing and confirming
        // it themselves.
        act(p.bob(), p.settlementId(), "confirm").andExpect(status().isForbidden());
        act(p.alice(), p.settlementId(), "confirm").andExpect(status().isOk());
    }

    @Test
    void onlyThePayerCanMarkPaidOrCancel() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);

        act(p.alice(), p.settlementId(), "mark-paid").andExpect(status().isForbidden());
        act(p.alice(), p.settlementId(), "cancel").andExpect(status().isForbidden());
        act(p.bob(), p.settlementId(), "cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void rejectingLeavesTheDebtExactlyWhereItWas() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);
        act(p.bob(), p.settlementId(), "mark-paid").andExpect(status().isOk());

        act(p.alice(), p.settlementId(), "reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // Nothing was posted, so nothing moved.
        assertThat(netOf(p.groupId(), p.bob(), p.alice())).isEqualTo(-10_000L);
        assertThat(ledger.postingsFor(p.groupId())).hasSize(3);
    }

    @Test
    void aCancelledSettlementCannotThenBeConfirmed() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);
        act(p.bob(), p.settlementId(), "cancel").andExpect(status().isOk());

        act(p.alice(), p.settlementId(), "confirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void theSecondOfTwoPendingSettlementsFailsAtConfirmation() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);

        // Both were valid when started -- pending ones are deliberately not held against
        // the limit (LLD 7.2) -- so the check has to catch it here instead.
        String second = JsonPath.read(mockMvc.perform(
                        post("/api/v1/groups/" + p.groupId() + "/settlements")
                                .header("Authorization", "Bearer " + p.bob().token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"toUser\":\"" + p.alice().id()
                                        + "\",\"amountPaise\":10000,\"method\":\"CASH\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        act(p.bob(), p.settlementId(), "mark-paid").andExpect(status().isOk());
        act(p.bob(), second, "mark-paid").andExpect(status().isOk());

        act(p.alice(), p.settlementId(), "confirm").andExpect(status().isOk());
        act(p.alice(), second, "confirm")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_OUTSTANDING"))
                // Alice is the one being paid. She used to be told "you do not owe this
                // group anything", which is true, about somebody else, and no answer to
                // what she just clicked. This test asserted only the code, which is how
                // that survived being read by a real person.
                .andExpect(jsonPath("$.detail").value(
                        "That debt is already settled, so there is nothing left to pay"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("You"))));

        assertThat(netOf(p.groupId(), p.alice(), p.alice())).isZero();
    }

    /** Finding C9. */
    @Test
    void voidingASettledExpenseLeavesACoherentOverpaidState() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);
        act(p.bob(), p.settlementId(), "mark-paid").andExpect(status().isOk());
        act(p.alice(), p.settlementId(), "confirm").andExpect(status().isOk());
        assertThat(netOf(p.groupId(), p.bob(), p.alice())).isZero();

        String expenseId = JsonPath.read(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/groups/" + p.groupId() + "/expenses")
                                .header("Authorization", "Bearer " + p.alice().token()))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + p.alice().token())
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        // Bob paid for a dinner that no longer exists, so the group owes him. That is a
        // legal state, not an error: the balance flips sign and the UI reads "Alice owes
        // Bob" rather than inventing a refund nobody asked for.
        assertThat(netOf(p.groupId(), p.bob(), p.alice())).isEqualTo(10_000L);
        assertThat(netOf(p.groupId(), p.alice(), p.alice())).isEqualTo(-10_000L);

        assertThat(balances.of(p.groupId(), p.alice().id()).pairwise())
                .singleElement()
                .satisfies(debt -> {
                    assertThat(debt.from()).isEqualTo(p.alice().id());
                    assertThat(debt.to()).isEqualTo(p.bob().id());
                    assertThat(debt.amount().paise()).isEqualTo(10_000L);
                });

        // And the whole group still sums to zero, overpaid or not.
        assertThat(ledger.postingsFor(p.groupId()).stream()
                .mapToLong(posting -> posting.amount().paise()).sum()).isZero();
    }

    @Test
    void theOverpaidGroupCanBeSettledBackTheOtherWay() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);
        act(p.bob(), p.settlementId(), "mark-paid").andExpect(status().isOk());
        act(p.alice(), p.settlementId(), "confirm").andExpect(status().isOk());

        String expenseId = JsonPath.read(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/groups/" + p.groupId() + "/expenses")
                                .header("Authorization", "Bearer " + p.alice().token()))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + p.alice().token())
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        // The reverse debt is a real debt: Alice can now settle it, which is what makes
        // "overpaid" a state the product can get out of rather than a dead end.
        mockMvc.perform(post("/api/v1/groups/" + p.groupId() + "/settlements")
                        .header("Authorization", "Bearer " + p.alice().token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUser\":\"" + p.bob().id()
                                + "\",\"amountPaise\":10000,\"method\":\"CASH\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aStrangerCannotActOnASettlement() throws Exception {
        Pair p = owedAndSettling(20_000L, 10_000L);
        Account mallory = newUser("Mallory");

        act(mallory, p.settlementId(), "mark-paid").andExpect(status().isNotFound());
        act(mallory, p.settlementId(), "confirm").andExpect(status().isNotFound());
    }
}
