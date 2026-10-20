package com.splitledger.group.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The invite flow end to end, against a real PostgreSQL -- which is the only place the
 * partial unique index on one open invite per (group, email) actually exists, so it is
 * the only place re-inviting can be shown to work.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=an-invite-test-secret-long-enough-for-hs256",
        "app.invites.base-url=https://app.test",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000",
        "app.rate-limit.limits.invite-create.capacity=1000",
        "app.rate-limit.limits.invite-lookup.capacity=1000"
})
class InviteFlowIT {

    @Autowired
    private MockMvc mockMvc;

    private record Account(String token, UUID id, String email) {
    }

    private Account newUser(String label) throws Exception {
        return newUser(label, label.toLowerCase() + "-" + System.nanoTime() + "@example.com");
    }

    private Account newUser(String label, String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"name\":\"" + label
                                + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Account(JsonPath.read(body, "$.accessToken"),
                UUID.fromString(JsonPath.read(body, "$.user.id")), email);
    }

    private String newGroup(Account owner, String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** The invite URL, from the one response that carries it. */
    private String invite(Account admin, String groupId, String email) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.url");
    }

    private static String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    void anInvitedUserCanSeeWhatTheInviteIsForThenAcceptAndJoin() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Flat 402");
        String bobEmail = "bob-" + System.nanoTime() + "@example.com";

        String url = invite(alice, groupId, bobEmail);
        assertThat(url).startsWith("https://app.test/invite/");
        String token = tokenOf(url);

        // Public on purpose: the landing page has to say which group before asking
        // anyone to sign in. No Authorization header here.
        mockMvc.perform(get("/api/v1/invites/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("Flat 402"))
                .andExpect(jsonPath("$.email").value(bobEmail));

        Account bob = newUser("Bob", bobEmail);
        mockMvc.perform(post("/api/v1/invites/" + token + "/accept")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId));

        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(2));

        // Accepting joins as a member, never as an admin.
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + bob.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carol@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anInviteIsSingleUseAndCannotBeReplayed() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Goa");
        String bobEmail = "bob-" + System.nanoTime() + "@example.com";
        String token = tokenOf(invite(alice, groupId, bobEmail));
        Account bob = newUser("Bob", bobEmail);

        mockMvc.perform(post("/api/v1/invites/" + token + "/accept")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/invites/" + token + "/accept")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isNotFound());
        // And the spent link gives nothing away either.
        mockMvc.perform(get("/api/v1/invites/" + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void holdingTheLinkIsNotEnoughIfItWasAddressedToSomeoneElse() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Trek");
        String token = tokenOf(invite(alice, groupId, "bob-" + System.nanoTime() + "@example.com"));

        Account carol = newUser("Carol");
        mockMvc.perform(post("/api/v1/invites/" + token + "/accept")
                        .header("Authorization", "Bearer " + carol.token()))
                .andExpect(status().isForbidden());

        // Nor can she burn it by declining on the invited person's behalf.
        mockMvc.perform(post("/api/v1/invites/" + token + "/decline")
                        .header("Authorization", "Bearer " + carol.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.members.length()").value(1));
    }

    @Test
    void acceptingRequiresASessionEvenWithAValidLink() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Concert");
        String token = tokenOf(invite(alice, groupId, "bob-" + System.nanoTime() + "@example.com"));

        mockMvc.perform(post("/api/v1/invites/" + token + "/accept"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theAdminListingNeverCarriesTheToken() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Dinner");
        String bobEmail = "bob-" + System.nanoTime() + "@example.com";
        String token = tokenOf(invite(alice, groupId, bobEmail));

        String listing = mockMvc.perform(get("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value(bobEmail))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        // Otherwise "who have we invited?" would be a way to recover every live link.
        assertThat(listing).doesNotContain(token);
    }

    @Test
    void reInvitingReplacesTheOpenLinkRatherThanStackingAnother() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Reunion");
        String bobEmail = "bob-" + System.nanoTime() + "@example.com";

        String first = tokenOf(invite(alice, groupId, bobEmail));
        // Differs in case and padding; the same person, so the same open invite.
        String second = tokenOf(invite(alice, groupId, " " + bobEmail.toUpperCase() + " "));
        assertThat(second).isNotEqualTo(first);

        Account bob = newUser("Bob", bobEmail);
        // Revoking by re-inviting has to actually stop the old link, or revocation is
        // cosmetic.
        mockMvc.perform(post("/api/v1/invites/" + first + "/accept")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/invites/" + second + "/accept")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk());
    }

    @Test
    void anAdminCanRevokeAPendingInvite() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Picnic");
        String bobEmail = "bob-" + System.nanoTime() + "@example.com";
        String token = tokenOf(invite(alice, groupId, bobEmail));

        String inviteId = JsonPath.read(mockMvc.perform(
                        get("/api/v1/groups/" + groupId + "/invites")
                                .header("Authorization", "Bearer " + alice.token()))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/invites/" + inviteId)
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isNoContent());

        Account bob = newUser("Bob", bobEmail);
        mockMvc.perform(post("/api/v1/invites/" + token + "/accept")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invitingAnExistingMemberIsRejected() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Office");

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + alice.email() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anInviteToANonMemberOfSomeOtherGroupIsInvisibleToItsAdmin() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        String aliceGroup = newGroup(alice, "Alice's flat");
        String malloryGroup = newGroup(mallory, "Mallory's flat");
        String token = tokenOf(invite(alice, aliceGroup,
                "bob-" + System.nanoTime() + "@example.com"));

        String inviteId = JsonPath.read(mockMvc.perform(
                        get("/api/v1/groups/" + aliceGroup + "/invites")
                                .header("Authorization", "Bearer " + alice.token()))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        // Mallory administers a group, just not this one. Her own group id in the path
        // must not let her revoke an invitation that belongs to Alice's.
        mockMvc.perform(delete("/api/v1/groups/" + malloryGroup + "/invites/" + inviteId)
                        .header("Authorization", "Bearer " + mallory.token()))
                .andExpect(status().isNotFound());

        // Still live.
        mockMvc.perform(get("/api/v1/invites/" + token)).andExpect(status().isOk());
    }

    @Test
    void anInvitedUserCanDecline() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Cricket");
        String bobEmail = "bob-" + System.nanoTime() + "@example.com";
        String token = tokenOf(invite(alice, groupId, bobEmail));
        Account bob = newUser("Bob", bobEmail);

        mockMvc.perform(post("/api/v1/invites/" + token + "/decline")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$[0].status").value("DECLINED"));
    }

    @Test
    void aNonMemberCannotInviteAnyone() throws Exception {
        Account alice = newUser("Alice");
        Account mallory = newUser("Mallory");
        String groupId = newGroup(alice, "Private");

        // 404 and not 403: a stranger must not learn the group exists.
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + mallory.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMalformedEmailIsRejectedBeforeAnyInviteIsIssued() throws Exception {
        Account alice = newUser("Alice");
        String groupId = newGroup(alice, "Validation");

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/invites")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
