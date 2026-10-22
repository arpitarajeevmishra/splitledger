package com.splitledger.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.balance.application.GroupBalances;
import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.balance.domain.PairwiseDebt;
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
    void aNonMemberGetsA404RatherThanTheGroupsFinances() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        UUID groupId = newGroup(alice, "Private");

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/balances")
                        .header("Authorization", "Bearer " + mallory.token()))
                .andExpect(status().isNotFound());
    }
}
