package com.splitledger.expense.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.balance.application.GroupBalances;
import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import com.splitledger.ledger.application.LedgerService;
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
 * Corrections: void and replace.
 *
 * <p>The acceptance criterion is "void restores balances <b>exactly</b>", and exactly is
 * the operative word -- an expense split three ways leaves an odd paisa somewhere, and a
 * reversal that recomputed the split rather than mirroring the postings would put it
 * somewhere else and leave a one-paisa ghost behind.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-correction-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class ExpenseCorrectionIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

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
                        .content("{\"name\":\"Flat 402\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private static String body(UUID payer, long amountPaise, List<UUID> participants) {
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

    private String create(Account payer, UUID groupId, long amountPaise, List<UUID> participants)
            throws Exception {
        return mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + payer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payer.id(), amountPaise, participants)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private record Trio(Account alice, Account bob, Account carol, UUID groupId) {
    }

    private Trio trio() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        Account carol = newUser("Carol");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.joinOrRejoin(groupId, carol.id(), Role.MEMBER);
        return new Trio(alice, bob, carol, groupId);
    }

    @Test
    void voidingRestoresEveryBalanceToExactlyZero() throws Exception {
        Trio t = trio();
        // An amount that does not divide by three, so the odd paisa has to come back to
        // the same person it went to.
        String created = create(t.alice(), t.groupId(), 90_001L,
                List.of(t.alice().id(), t.bob().id(), t.carol().id()));
        String expenseId = JsonPath.read(created, "$.id");

        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.alice().token())
                        .header("If-Match", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));

        GroupBalances after = balances.of(t.groupId(), t.alice().id());
        assertThat(after.net()).allMatch(MemberBalance::isSettled);
        assertThat(after.pairwise()).isEmpty();
        // Nothing deleted: the original postings and their mirror both remain. Four
        // each -- the payer is credited the whole amount and then debited their own
        // share, which is a separate posting from the other two participants'.
        assertThat(ledger.postingsFor(t.groupId())).hasSize(8);
    }

    @Test
    void theVoidedExpenseIsStillReadableWithWhatItSaid() throws Exception {
        Trio t = trio();
        String expenseId = JsonPath.read(create(t.alice(), t.groupId(), 90_000L,
                List.of(t.alice().id(), t.bob().id())), "$.id");

        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.alice().token())
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        // History is preserved rather than deleted -- that is the whole argument for
        // correcting by reversal.
        mockMvc.perform(get("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.bob().token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaise").value(90_000))
                .andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void aStaleIfMatchIsRefusedWithA409() throws Exception {
        Trio t = trio();
        String expenseId = JsonPath.read(create(t.alice(), t.groupId(), 90_000L,
                List.of(t.alice().id(), t.bob().id())), "$.id");

        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.alice().token())
                        .header("If-Match", "99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // Refused means untouched.
        assertThat(balances.of(t.groupId(), t.alice().id()).pairwise()).hasSize(1);
    }

    @Test
    void anExpenseCannotBeVoidedTwice() throws Exception {
        Trio t = trio();
        String expenseId = JsonPath.read(create(t.alice(), t.groupId(), 90_000L,
                List.of(t.alice().id(), t.bob().id())), "$.id");

        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.alice().token())
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        // Version moved on, so this reads as a conflict before it reads as a state error.
        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.alice().token())
                        .header("If-Match", "0"))
                .andExpect(status().isConflict());

        assertThat(balances.of(t.groupId(), t.alice().id()).net())
                .allMatch(MemberBalance::isSettled);
    }

    @Test
    void replacingLeavesOnlyTheCorrectedAmountInTheBalances() throws Exception {
        Trio t = trio();
        String expenseId = JsonPath.read(create(t.alice(), t.groupId(), 90_000L,
                List.of(t.alice().id(), t.bob().id())), "$.id");

        String replaced = mockMvc.perform(put("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.alice().token())
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(t.alice().id(), 50_000L,
                                List.of(t.alice().id(), t.bob().id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaise").value(50_000))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(replaced, "$.id")).isNotEqualTo(expenseId);

        // 25,000 and not 45,000 + 25,000: the original was reversed, not added to.
        GroupBalances after = balances.of(t.groupId(), t.alice().id());
        assertThat(after.pairwise()).hasSize(1);
        assertThat(after.pairwise().getFirst().amount().paise()).isEqualTo(25_000L);
        assertThat(after.net().stream().mapToLong(b -> b.net().paise()).sum()).isZero();
    }

    @Test
    void aQuotedEtagIsAccepted() throws Exception {
        Trio t = trio();
        String expenseId = JsonPath.read(create(t.alice(), t.groupId(), 10_000L,
                List.of(t.alice().id(), t.bob().id())), "$.id");

        // An ETag is conventionally quoted, and a client library may well send it that way.
        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.alice().token())
                        .header("If-Match", "\"0\""))
                .andExpect(status().isOk());
    }

    @Test
    void aNonMemberCannotVoidAnExpense() throws Exception {
        Trio t = trio();
        Account mallory = newUser("Mallory");
        String expenseId = JsonPath.read(create(t.alice(), t.groupId(), 10_000L,
                List.of(t.alice().id(), t.bob().id())), "$.id");

        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + mallory.token())
                        .header("If-Match", "0"))
                .andExpect(status().isNotFound());
    }

    @Test
    void bothDerivationsStillAgreeAfterACorrection() throws Exception {
        Trio t = trio();
        String expenseId = JsonPath.read(create(t.alice(), t.groupId(), 90_001L,
                List.of(t.alice().id(), t.bob().id(), t.carol().id())), "$.id");
        create(t.bob(), t.groupId(), 7L, List.of(t.alice().id(), t.bob().id()));

        mockMvc.perform(put("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + t.alice().token())
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(t.carol().id(), 33_333L,
                                List.of(t.alice().id(), t.bob().id(), t.carol().id()))))
                .andExpect(status().isOk());

        GroupBalances after = balances.of(t.groupId(), t.alice().id());
        java.util.Map<UUID, Long> fromPairwise = new java.util.HashMap<>();
        after.pairwise().forEach(debt -> {
            fromPairwise.merge(debt.from(), -debt.amount().paise(), Long::sum);
            fromPairwise.merge(debt.to(), debt.amount().paise(), Long::sum);
        });
        for (MemberBalance balance : after.net()) {
            assertThat(fromPairwise.getOrDefault(balance.userId(), 0L))
                    .as("net for %s after correction", balance.userId())
                    .isEqualTo(balance.net().paise());
        }
    }
}
