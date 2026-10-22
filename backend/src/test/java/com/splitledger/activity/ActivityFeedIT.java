package com.splitledger.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.activity.application.ActivityService;
import com.splitledger.activity.domain.ActivityEntry;
import com.splitledger.activity.domain.ActivityKind;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import java.time.Instant;
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
 * The feed, and the claim it has to live up to: every balance change has an entry naming
 * who did what.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=an-activity-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class ActivityFeedIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

    @Autowired
    private ActivityService activity;

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

    private String addExpense(Account payer, UUID groupId, long amountPaise, List<UUID> people)
            throws Exception {
        StringBuilder participants = new StringBuilder();
        for (UUID person : people) {
            if (!participants.isEmpty()) {
                participants.append(',');
            }
            participants.append("{\"userId\":\"").append(person).append("\"}");
        }
        return JsonPath.read(mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .header("Authorization", "Bearer " + payer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Dinner\",\"amountPaise\":" + amountPaise
                                + ",\"paidBy\":\"" + payer.id()
                                + "\",\"expenseDate\":\"2026-01-10\","
                                + "\"splitType\":\"EQUAL\",\"participants\":["
                                + participants + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void everyBalanceChangeHasAnEntryNamingWhoDidWhat() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);

        String expenseId = addExpense(alice, groupId, 90_000L, List.of(alice.id(), bob.id()));
        // Bob voids it, not Alice -- which is the case the feed has to get right.
        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + bob.token())
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        List<ActivityEntry> feed = activity.feed(groupId, alice.id(), 0, 50);

        ActivityEntry added = feed.stream()
                .filter(entry -> entry.kind() == ActivityKind.EXPENSE_ADDED)
                .findFirst().orElseThrow();
        assertThat(added.actorId()).isEqualTo(alice.id());
        assertThat(added.amountPaise()).isEqualTo(90_000L);
        assertThat(added.description()).isEqualTo("Dinner");

        ActivityEntry voided = feed.stream()
                .filter(entry -> entry.kind() == ActivityKind.EXPENSE_VOIDED)
                .findFirst().orElseThrow();
        // The person who voided it, not the person who created it.
        assertThat(voided.actorId()).isEqualTo(bob.id());
        assertThat(voided.subjectId()).isEqualTo(UUID.fromString(expenseId));

        assertThat(feed).filteredOn(ActivityEntry::movedMoney).hasSize(2);
    }

    @Test
    void aVoidIsTimestampedWhenItHappenedNotWhenTheExpenseWasCreated() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice);
        String expenseId = addExpense(alice, groupId, 10_000L, List.of(alice.id()));

        Instant beforeVoid = Instant.now();
        mockMvc.perform(delete("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + alice.token())
                        .header("If-Match", "0"))
                .andExpect(status().isOk());

        List<ActivityEntry> feed = activity.feed(groupId, alice.id(), 0, 50);
        ActivityEntry added = feed.stream()
                .filter(entry -> entry.kind() == ActivityKind.EXPENSE_ADDED)
                .findFirst().orElseThrow();
        ActivityEntry voided = feed.stream()
                .filter(entry -> entry.kind() == ActivityKind.EXPENSE_VOIDED)
                .findFirst().orElseThrow();

        // Falling back to created_at would put the void before its own expense.
        assertThat(voided.at()).isAfterOrEqualTo(added.at());
        assertThat(voided.at()).isAfterOrEqualTo(beforeVoid.minusSeconds(5));
        // Newest first, so the void leads.
        assertThat(feed.getFirst().kind()).isEqualTo(ActivityKind.EXPENSE_VOIDED);
    }

    @Test
    void joiningAndLeavingAreRecordedWithoutClaimingToMoveMoney() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        membership.remove(groupId, bob.id(), bob.id());

        List<ActivityEntry> feed = activity.feed(groupId, alice.id(), 0, 50);

        assertThat(feed).extracting(ActivityEntry::kind)
                .contains(ActivityKind.MEMBER_JOINED, ActivityKind.MEMBER_LEFT);
        assertThat(feed).filteredOn(entry -> entry.kind() == ActivityKind.MEMBER_JOINED)
                .allSatisfy(entry -> assertThat(entry.movedMoney()).isFalse());
    }

    @Test
    void aReplacementShowsBothTheVoidAndTheNewExpense() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        String expenseId = addExpense(alice, groupId, 90_000L, List.of(alice.id(), bob.id()));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/expenses/" + expenseId)
                        .header("Authorization", "Bearer " + alice.token())
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Dinner, corrected\",\"amountPaise\":50000,"
                                + "\"paidBy\":\"" + alice.id()
                                + "\",\"expenseDate\":\"2026-01-10\",\"splitType\":\"EQUAL\","
                                + "\"participants\":[{\"userId\":\"" + alice.id()
                                + "\"},{\"userId\":\"" + bob.id() + "\"}]}"))
                .andExpect(status().isOk());

        List<ActivityEntry> feed = activity.feed(groupId, alice.id(), 0, 50);

        // A correction is two balance changes, and the feed shows both rather than
        // quietly replacing one entry with another.
        assertThat(feed).filteredOn(entry -> entry.kind() == ActivityKind.EXPENSE_ADDED)
                .hasSize(2);
        assertThat(feed).filteredOn(entry -> entry.kind() == ActivityKind.EXPENSE_VOIDED)
                .hasSize(1);
    }

    @Test
    void theFeedPagesWithoutRepeatingOrSkipping() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice);
        for (int i = 0; i < 7; i++) {
            addExpense(alice, groupId, 1_000L + i, List.of(alice.id()));
        }

        List<ActivityEntry> first = activity.feed(groupId, alice.id(), 0, 3);
        List<ActivityEntry> second = activity.feed(groupId, alice.id(), 1, 3);

        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3);
        assertThat(first).extracting(ActivityEntry::subjectId)
                .doesNotContainAnyElementsOf(
                        second.stream().map(ActivityEntry::subjectId).toList());
    }

    @Test
    void anOversizedPageIsCappedRatherThanRefused() throws Exception {
        Account alice = newUser("Alice");
        UUID groupId = newGroup(alice);
        addExpense(alice, groupId, 1_000L, List.of(alice.id()));

        // A client asking for everything gets a bounded answer, not an error and not the
        // whole table.
        assertThat(activity.feed(groupId, alice.id(), 0, 10_000)).isNotEmpty();
    }

    @Test
    void theEndpointServesTheFeedAndRefusesNonMembers() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        UUID groupId = newGroup(alice);
        addExpense(alice, groupId, 10_000L, List.of(alice.id()));

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/activity")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("EXPENSE_ADDED"))
                .andExpect(jsonPath("$[0].movedMoney").value(true));

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/activity")
                        .header("Authorization", "Bearer " + mallory.token()))
                .andExpect(status().isNotFound());
    }
}
