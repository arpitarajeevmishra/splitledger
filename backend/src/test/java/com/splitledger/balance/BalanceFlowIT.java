package com.splitledger.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.balance.application.GroupBalances;
import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.balance.domain.PairwiseDebt;
import com.splitledger.balance.domain.Transfer;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The balances, and the reason to believe them.
 *
 * <p>Net comes from the ledger, pairwise from the expenses that created the debt. They are
 * computed from different tables by different SQL, so agreeing is evidence rather than
 * tautology -- and that agreement is this story's acceptance criterion.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-balance-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class BalanceFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

    @Autowired
    private com.splitledger.settlement.application.SettlementService settlements;

    @Autowired
    private BalanceService balances;

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

    private void expense(Account payer, UUID groupId, long amountPaise, List<UUID> participants)
            throws Exception {
        StringBuilder people = new StringBuilder();
        for (UUID participant : participants) {
            if (!people.isEmpty()) {
                people.append(',');
            }
            people.append("{\"userId\":\"").append(participant).append("\"}");
        }
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + payer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Dinner\",\"amountPaise\":" + amountPaise
                                + ",\"paidBy\":\"" + payer.id()
                                + "\",\"expenseDate\":\"2026-01-10\","
                                + "\"splitType\":\"EQUAL\",\"participants\":["
                                + people + "]}"))
                .andExpect(status().isCreated());
    }

    private static long netOf(GroupBalances balances, UUID userId) {
        return balances.net().stream()
                .filter(balance -> balance.userId().equals(userId))
                .mapToLong(balance -> balance.net().paise())
                .findFirst().orElseThrow();
    }

    /** The net each user would have if you only had the pairwise list to go on. */
    private static Map<UUID, Long> netFromPairwise(List<PairwiseDebt> pairwise) {
        Map<UUID, Long> net = new HashMap<>();
        for (PairwiseDebt debt : pairwise) {
            net.merge(debt.from(), -debt.amount().paise(), Long::sum);
            net.merge(debt.to(), debt.amount().paise(), Long::sum);
        }
        return net;
    }

    @Test
    void theWorkedExampleFromTheDesign() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);

        // LLD 5.1: Alice pays 900, split equally three ways.
        expense(alice, groupId, 90_000L, List.of(alice.id(), bob.id(), carol.id()));

        GroupBalances result = balances.of(groupId, alice.id());
        assertThat(netOf(result, alice.id())).isEqualTo(60_000L);
        assertThat(netOf(result, bob.id())).isEqualTo(-30_000L);
        assertThat(netOf(result, carol.id())).isEqualTo(-30_000L);

        assertThat(result.pairwise()).hasSize(2);
        assertThat(result.pairwise()).allSatisfy(debt -> {
            assertThat(debt.to()).isEqualTo(alice.id());
            assertThat(debt.amount().paise()).isEqualTo(30_000L);
        });
    }

    @Test
    void theTwoDerivationsAgree() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice, "Shared living");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);

        // Several expenses with different payers and participant sets, and an amount that
        // does not divide evenly, so the odd paisa has to land somewhere in both
        // derivations and land in the same place.
        expense(alice, groupId, 90_001L, List.of(alice.id(), bob.id(), carol.id()));
        expense(bob, groupId, 45_000L, List.of(alice.id(), bob.id()));
        expense(carol, groupId, 7L, List.of(alice.id(), bob.id(), carol.id()));

        GroupBalances result = balances.of(groupId, alice.id());
        Map<UUID, Long> fromPairwise = netFromPairwise(result.pairwise());

        for (MemberBalance balance : result.net()) {
            // The ledger says one thing, the expense rows say another, and they must be
            // the same thing.
            assertThat(fromPairwise.getOrDefault(balance.userId(), 0L))
                    .as("net for %s", balance.userId())
                    .isEqualTo(balance.net().paise());
        }
        assertThat(result.net().stream().mapToLong(b -> b.net().paise()).sum()).isZero();
    }

    @Test
    void aSettledMemberIsListedRatherThanOmitted() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice, "Quiet flat");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        // Nobody has spent anything: both are at zero, and both should still appear.
        GroupBalances result = balances.of(groupId, alice.id());

        assertThat(result.net()).hasSize(2);
        assertThat(result.net()).allMatch(MemberBalance::isSettled);
        assertThat(result.pairwise()).isEmpty();
    }

    @Test
    void anExpenseSplitOnlyWithYourselfOwesNobody() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice, "Solo");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        expense(alice, groupId, 50_000L, List.of(alice.id()));

        GroupBalances result = balances.of(groupId, alice.id());
        // She paid for something only she consumed: no debt, and the ledger nets out.
        assertThat(netOf(result, alice.id())).isZero();
        assertThat(result.pairwise()).isEmpty();
    }

    @Test
    void theEndpointServesBothDerivations() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 10_000L, List.of(alice.id(), bob.id()));

        String body = mockMvc.perform(get("/api/v1/groups/" + groupId + "/balances")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((int) JsonPath.read(body, "$.net.length()")).isEqualTo(2);
        assertThat((int) JsonPath.read(body, "$.pairwise.length()")).isEqualTo(1);
        assertThat((int) JsonPath.read(body, "$.pairwise[0].amountPaise")).isEqualTo(5_000);
    }

    @Test
    void aCircleOfDebtsCollapsesIntoFewerPayments() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice, "Circle");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);

        // A pays for A+B, B pays for B+C, C pays for C+A: everyone owes someone, and
        // pairwise there are three debts going round in a circle.
        expense(alice, groupId, 30_000L, List.of(alice.id(), bob.id()));
        expense(bob, groupId, 30_000L, List.of(bob.id(), carol.id()));
        expense(carol, groupId, 30_000L, List.of(carol.id(), alice.id()));

        GroupBalances before = balances.of(groupId, alice.id());
        assertThat(before.net()).allMatch(MemberBalance::isSettled);

        // Everyone paid the same and consumed the same, so the circle cancels entirely
        // and the plan is empty -- which pairwise debts alone would never tell you.
        assertThat(balances.simplified(groupId, alice.id())).isEmpty();
    }

    @Test
    void theSimplifiedPlanSettlesEveryoneWhenApplied() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice, "Trip");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);

        expense(alice, groupId, 90_001L, List.of(alice.id(), bob.id(), carol.id()));
        expense(bob, groupId, 15_000L, List.of(alice.id(), bob.id()));

        Map<UUID, Long> after = new HashMap<>();
        balances.of(groupId, alice.id()).net()
                .forEach(balance -> after.put(balance.userId(), balance.net().paise()));
        List<Transfer> plan = balances.simplified(groupId, alice.id());

        for (Transfer transfer : plan) {
            after.merge(transfer.from(), transfer.amount().paise(), Long::sum);
            after.merge(transfer.to(), -transfer.amount().paise(), Long::sum);
        }
        assertThat(after.values()).allSatisfy(balance -> assertThat(balance).isZero());
        // Three people, so at most two payments.
        assertThat(plan).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void theSimplifiedEndpointSaysWhenNobodyOwesAnything() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice, "Quiet");
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/balances/simplified")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"settled\":true")));
    }

    @Test
    void aNonMemberCannotReadTheSimplifiedPlan() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        UUID groupId = newGroup(alice, "Private");

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/balances/simplified")
                        .header("Authorization", "Bearer " + mallory.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNonMemberGetsA404RatherThanTheGroupsFinances() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        UUID groupId = newGroup(alice, "Private");

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/balances")
                        .header("Authorization", "Bearer " + mallory.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theNetPositionAcrossGroupsAddsUpAndKeepsItsSign() throws Exception {
        // The dashboard's one number. It was hardcoded to zero until this endpoint
        // existed, so everybody was told they were square whatever they owed.
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");

        // Bob owes Alice 450 in one group...
        UUID flat = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(flat, bob.id(), Role.MEMBER);
        expense(alice, flat, 90_000L, List.of(alice.id(), bob.id()));

        // ...and is owed 300 in another, where he paid.
        UUID trip = newGroup(bob, "Goa trip");
        membership.joinOrRejoin(trip, alice.id(), Role.MEMBER);
        expense(bob, trip, 60_000L, List.of(alice.id(), bob.id()));

        // Net: he owes 450 and is owed 300, so he owes 150 overall, across two groups.
        mockMvc.perform(get("/api/v1/balances/me")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netPaise").value(-15_000))
                .andExpect(jsonPath("$.unsettledGroups").value(2))
                .andExpect(jsonPath("$.settled").value(false));

        // And the mirror image for Alice, which is what makes the sign meaningful rather
        // than a convention nobody checked.
        mockMvc.perform(get("/api/v1/balances/me")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netPaise").value(15_000))
                .andExpect(jsonPath("$.settled").value(false));
    }

    @Test
    void someoneWithNothingOwedIsSettledRatherThanAbsent() throws Exception {
        Account alone = newUser("Alone");

        // No groups, no postings: zero is the right answer and the endpoint has to say so
        // rather than omit the figure.
        mockMvc.perform(get("/api/v1/balances/me")
                        .header("Authorization", "Bearer " + alone.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netPaise").value(0))
                .andExpect(jsonPath("$.unsettledGroups").value(0))
                .andExpect(jsonPath("$.settled").value(true));
    }

    @Test
    void aGroupThatHasBeenSettledStopsCounting() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID flat = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(flat, bob.id(), Role.MEMBER);
        expense(alice, flat, 90_000L, List.of(alice.id(), bob.id()));

        com.splitledger.settlement.domain.Settlement cash = settlements.create(flat, bob.id(),
                alice.id(), 45_000L, com.splitledger.common.payment.PaymentMethod.CASH);
        settlements.markPaid(cash.id(), bob.id());
        settlements.confirm(cash.id(), alice.id());

        mockMvc.perform(get("/api/v1/balances/me")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netPaise").value(0))
                // The query drops settled groups, so the count goes with the figure.
                .andExpect(jsonPath("$.unsettledGroups").value(0))
                .andExpect(jsonPath("$.settled").value(true));
    }

    @Test
    void itIsAlwaysTheCallersOwnPosition() throws Exception {
        // No user id in the path, so there is nothing to tamper with. Worth asserting
        // rather than assuming: this is the one endpoint that returns a person's finances
        // without naming a group to be a member of.
        Account alice = newUser("Alice");
        mockMvc.perform(get("/api/v1/balances/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/balances/me")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk());
    }
}
