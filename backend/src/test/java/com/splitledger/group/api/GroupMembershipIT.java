package com.splitledger.group.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import java.util.UUID;
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
        "app.jwt.secret=a-membership-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class GroupMembershipIT {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Joining goes through the invite flow (SL-204), which does not exist yet, so a second
     * member is arranged through the application layer rather than the API.
     */
    @Autowired
    private GroupMembershipService membership;

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

    private String newGroup(Account owner, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void aMemberCanLeaveAndTheGroupShrinks() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        String groupId = newGroup(alice, "Flat 402");
        membership.joinOrRejoin(UUID.fromString(groupId), bob.id(), Role.MEMBER);

        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.members.length()").value(2));

        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + bob.id())
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.members.length()").value(1));

        // Having left, the group is now invisible to him -- 404, not 403.
        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAdminCanRemoveAMember() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        String groupId = newGroup(alice, "Nordic Trip");
        membership.joinOrRejoin(UUID.fromString(groupId), bob.id(), Role.MEMBER);

        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + bob.id())
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isNoContent());
    }

    @Test
    void aPlainMemberCannotRemoveSomeoneElse() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        String groupId = newGroup(alice, "Dinner");
        membership.joinOrRejoin(UUID.fromString(groupId), bob.id(), Role.MEMBER);

        // 403, not 404: Bob is in the group, so hiding the reason would conceal nothing.
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + alice.id())
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void theLastAdminCannotLeave() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Solo");

        // Otherwise the group keeps its members but can never be administered again.
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + alice.id())
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }

    @Test
    void anOutsiderGets404RatherThanAnythingRevealing() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        String groupId = newGroup(alice, "Private");

        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + alice.id())
                        .header("Authorization", "Bearer " + mallory.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void removingSomeoneWhoWasNeverAMemberIs404() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Flat 402");

        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isNotFound());
    }
}
