package com.splitledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.balance.domain.Transfer;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
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

/**
 * Creating settlements, and the rule that makes simplification usable.
 *
 * <p>The decisive test is {@link #aTransferStraightFromTheSimplifiedPlanIsAccepted}: the
 * plan deliberately suggests payments between people with no direct debt, so validating
 * against pairwise figures would reject exactly the transfers the feature produces
 * (ADR-010). Nothing else here matters if that one does not hold.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-settlement-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class SettlementCreationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

    @Autowired
    private ExpenseService expenses;

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

    private UUID newGroup(Account owner) throws Exception {
        return UUID.fromString(JsonPath.read(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 402\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private void expense(Account payer, UUID groupId, long amountPaise, List<UUID> people) {
        expenses.record(groupId, payer.id(), "Dinner", amountPaise, payer.id(),
                LocalDate.of(2026, 1, 10), SplitType.EQUAL,
                people.stream().map(id -> new ExpenseService.Participant(id, null)).toList());
    }

    private static String body(UUID toUser, long amountPaise, String method) {
        return "{\"toUser\":\"" + toUser + "\",\"amountPaise\":" + amountPaise
                + ",\"method\":\"" + method + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions settle(Account payer,
            UUID groupId, UUID payee, long amountPaise, String method) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                .header("Authorization", "Bearer " + payer.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(payee, amountPaise, method)));
    }

    /** The one this story exists for. */
    @Test
    void aTransferStraightFromTheSimplifiedPlanIsAccepted() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);

        // Alice pays for herself and Bob; Bob pays for himself and Carol. Carol owes Bob
        // and Bob owes Alice -- Carol and Alice have no direct debt between them at all.
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));
        expense(bob, groupId, 20_000L, List.of(bob.id(), carol.id()));

        assertThat(balances.of(groupId, alice.id()).pairwise())
                .as("Carol and Alice share no expense")
                .noneSatisfy(debt -> {
                    assertThat(debt.from()).isEqualTo(carol.id());
                    assertThat(debt.to()).isEqualTo(alice.id());
                });

        List<Transfer> plan = balances.simplified(groupId, alice.id());
        Transfer suggested = plan.stream()
                .filter(transfer -> transfer.from().equals(carol.id()))
                .findFirst().orElseThrow();
        assertThat(suggested.to()).isEqualTo(alice.id());

        // Validating pairwise would reject this. Validating net accepts it, which is the
        // entire point of ADR-010.
        settle(carol, groupId, suggested.to(), suggested.amount().paise(), "CASH")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INITIATED"));
    }

    @Test
    void genuineOverpaymentIsRefusedWith422() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));

        // Bob owes 10,000.
        settle(bob, groupId, alice.id(), 10_001L, "CASH")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_OUTSTANDING"));

        settle(bob, groupId, alice.id(), 10_000L, "CASH")
                .andExpect(status().isCreated());
    }

    @Test
    void someoneWhoOwesNothingCannotSettle() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));

        // Alice is owed; she cannot pay Bob.
        settle(alice, groupId, bob.id(), 1_000L, "CASH")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_OUTSTANDING"));
    }

    @Test
    void theLimitIsTheSmallerOfWhatIsOwedAndWhatIsDue() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);

        // Alice is owed 10,000 in total; Carol owes 20,000. Carol may pay Alice at most
        // the 10,000 Alice is actually owed, not the 20,000 Carol owes the group.
        expense(alice, groupId, 20_000L, List.of(alice.id(), carol.id()));
        expense(bob, groupId, 20_000L, List.of(bob.id(), carol.id()));

        settle(carol, groupId, alice.id(), 10_001L, "CASH")
                .andExpect(status().isUnprocessableEntity());
        settle(carol, groupId, alice.id(), 10_000L, "CASH")
                .andExpect(status().isCreated());
    }

    @Test
    void aCardPaymentBelowTheGatewayMinimumIsRefusedByUsNotByTheGateway() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 100L, List.of(alice.id(), bob.id()));

        // Bob owes 50 paise. Cash is fine; a card payment is not, and the refusal names
        // the reason rather than surfacing an opaque gateway error later (finding C5).
        settle(bob, groupId, alice.id(), 50L, "RAZORPAY")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_BELOW_GATEWAY_MINIMUM"));

        settle(bob, groupId, alice.id(), 50L, "CASH")
                .andExpect(status().isCreated());
    }

    @Test
    void youCannotSettleWithYourself() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));

        settle(bob, groupId, bob.id(), 1_000L, "CASH")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aNonMemberCannotCreateASettlementAndGetsA404() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account mallory = newUser("Mallory");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));

        settle(mallory, groupId, alice.id(), 1_000L, "CASH")
                .andExpect(status().isNotFound());
    }

    @Test
    void payingSomeoneWhoHasLeftIsRefused() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));
        membership.remove(groupId, carol.id(), carol.id());

        // A debt to someone who left is settled by re-inviting them, not by paying into a
        // group they are no longer in.
        settle(bob, groupId, carol.id(), 1_000L, "CASH")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_MEMBER"));
    }

    @Test
    void creatingASettlementPostsNothingToTheLedger() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));

        settle(bob, groupId, alice.id(), 10_000L, "CASH").andExpect(status().isCreated());

        // Creating records an intent to pay. Posting here would let anyone clear a debt by
        // announcing they had paid it.
        assertThat(balances.of(groupId, alice.id()).net())
                .anySatisfy(balance -> assertThat(balance.net().paise()).isEqualTo(10_000L));
    }

    @Test
    void aRetriedCreateWithTheSameKeyMakesOneSettlement() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));
        String key = UUID.randomUUID().toString();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                            .header("Authorization", "Bearer " + bob.token())
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(alice.id(), 10_000L, "CASH")))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/settlements")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aSettlementIsReadableOnItsOwnForPolling() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expense(alice, groupId, 20_000L, List.of(alice.id(), bob.id()));

        String id = JsonPath.read(settle(bob, groupId, alice.id(), 10_000L, "CASH")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/settlements/" + id)
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaise").value(10_000))
                .andExpect(jsonPath("$.demoOnly").value(true));
    }
}
