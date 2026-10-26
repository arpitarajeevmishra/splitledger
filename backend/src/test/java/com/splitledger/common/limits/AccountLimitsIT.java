package com.splitledger.common.limits;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import java.util.List;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import java.time.Instant;
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
 * The per-account caps over HTTP (SL-906), with the numbers turned down so a test can
 * reach them.
 *
 * <p>Turned down rather than reached honestly: hitting the shipped cap would mean 200
 * expenses and 50 invitations per run. The numbers are configuration, so a test that
 * tightens them exercises exactly the code a production cap runs -- what is under test is
 * the enforcement and the response, not the value.
 *
 * <p>Against real PostgreSQL because every cap is a count read back from it, and because
 * the member cap is taken under a row lock that only exists there.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=an-account-limits-secret-long-enough-for-hs256",
        "app.invites.base-url=https://app.test",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000",
        "app.rate-limit.limits.invite-create.capacity=1000",
        "app.rate-limit.limits.invite-lookup.capacity=1000",
        "app.limits.groups-per-account=2",
        "app.limits.members-per-group=2",
        "app.limits.expenses-per-day=2"
})
class AccountLimitsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private record Account(String token, UUID id, String email) {
    }

    @Test
    void theGroupAfterTheCapIsRefusedWithA422ThatNamesTheCap() throws Exception {
        Account alice = newUser("Alice");
        newGroup(alice, "Flat 402");
        newGroup(alice, "Goa trip");

        mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"One too many\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED"))
                // The acceptance criterion is a *clear* 422: the detail has to say which
                // cap and where this account stands, or the only recourse is guessing.
                .andExpect(jsonPath("$.detail").value(
                        "This demo allows 2 groups per account, and yours has 2."));
    }

    @Test
    void theCapIsPerAccountAndNotGlobal() throws Exception {
        // Otherwise the first busy account closes group creation for everyone, which is a
        // denial of service with extra steps rather than a cap.
        Account alice = newUser("Alice");
        newGroup(alice, "Flat 402");
        newGroup(alice, "Goa trip");

        Account bob = newUser("Bob");
        newGroup(bob, "Bob's own");
    }

    @Test
    void anInvitationToAFullGroupCannotBeAccepted() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice, "Flat 402");

        Account bob = newUser("Bob");
        accept(invite(alice, groupId, bob.email()), bob).andExpect(status().isOk());

        // The group now holds its two: Alice and Bob. The invitation is created without
        // complaint -- an admin may send one, and the group might have room by the time it
        // is opened -- and refused at the moment it would add a third member.
        Account carol = newUser("Carol");
        accept(invite(alice, groupId, carol.email()), carol)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED"))
                .andExpect(jsonPath("$.detail").value(
                        "This demo allows 2 members in a group, and this one has 2."));
    }

    @Test
    void theExpenseAfterTheDailyCapIsRefused() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice, "Flat 402");

        expense(alice, groupId, 90_000L).andExpect(status().isCreated());
        expense(alice, groupId, 80_000L).andExpect(status().isCreated());

        expense(alice, groupId, 70_000L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED"))
                .andExpect(jsonPath("$.detail").value("This demo allows 2 expenses per account"
                        + " per day, and yours has recorded 2 in the last 24 hours."));
    }

    @Test
    void aCorrectionCountsAgainstTheDayLikeAnythingElseThatWritesARow() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice, "Flat 402");

        String first = JsonPath.read(expense(alice, groupId, 90_000L)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        // Replacing writes a second expense, so it spends the second of two. If corrections
        // were exempt, replace-in-a-loop would be an uncapped write path -- the cap is
        // about rows written, not expenses that stand.
        mockMvc.perform(put("/api/v1/expenses/" + first)
                        .header("Authorization", "Bearer " + alice.token())
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(equalSplit(alice.id(), 95_000L, List.of(alice.id()))))
                .andExpect(status().isOk());

        expense(alice, groupId, 70_000L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED"));
    }

    @Test
    void aVoidedExpenseStillCountsBecauseWritingItStillCost() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice, "Flat 402");

        String first = JsonPath.read(expense(alice, groupId, 90_000L)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        expense(alice, groupId, 80_000L).andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/expenses/" + first)
                        .header("Authorization", "Bearer " + alice.token())
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        // Voiding frees nothing. A cap that could be reset by undoing work would bound
        // nothing at all: void-and-record is a loop.
        expense(alice, groupId, 70_000L).andExpect(status().isUnprocessableEntity());
    }

    private ResultActions expense(Account actor,
            UUID groupId, long amountPaise) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                .header("Authorization", "Bearer " + actor.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(equalSplit(actor.id(), amountPaise, List.of(actor.id()))));
    }

    private static String equalSplit(UUID payer, long amountPaise, List<UUID> participants) {
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

    private Account newUser(String label) throws Exception {
        String email = label.toLowerCase() + "-" + System.nanoTime() + "@example.com";
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"name\":\"" + label
                                + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Account account = new Account(JsonPath.read(body, "$.accessToken"),
                UUID.fromString(JsonPath.read(body, "$.user.id")), email);
        verifyEmail(email);
        return account;
    }

    /**
     * Marks the account verified, which accepting an invitation now requires (SL-901).
     *
     * <p>Set directly rather than redeemed through a link, deliberately. These tests are
     * about invitations and about account caps; verification is a precondition they now
     * have, not the thing they are checking, and routing every one of them through a
     * mailer capture would bury what each is actually about. The real flow -- issue,
     * redeem, single use, expiry -- is covered end to end by {@code EmailVerificationIT}.
     *
     * <p>Before SL-901 these tests passed because nothing checked. They failed the moment
     * the check existed, which is the check working.
     */
    private void verifyEmail(String email) {
        User user = users.findByEmail(User.normaliseEmail(email)).orElseThrow();
        user.verifyEmail(Instant.now());
        users.save(user);
    }

    private UUID newGroup(Account owner, String name) throws Exception {
        return UUID.fromString(JsonPath.read(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private String invite(Account admin, UUID groupId, String email) throws Exception {
        String url = JsonPath.read(mockMvc.perform(post("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.url");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private ResultActions accept(String token,
            Account invitee) throws Exception {
        return mockMvc.perform(post("/api/v1/invites/" + token + "/accept")
                .header("Authorization", "Bearer " + invitee.token()));
    }
}
