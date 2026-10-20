package com.splitledger.group.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
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
        "app.jwt.secret=a-group-flow-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000"
})
class GroupFlowIT {

    @Autowired
    private MockMvc mockMvc;

    /** Registers an account and returns its access token. */
    private String newUser(String label) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + label + "-" + System.nanoTime()
                                + "@example.com\",\"name\":\"" + label + "\","
                                + "\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    @Test
    void createListAndViewAGroup() throws Exception {
        String alice = newUser("Alice");

        String created = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Flat 402  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Flat 402"))
                .andExpect(jsonPath("$.currency").value("INR"))
                // The creator must be an admin and a member in the same transaction: a
                // group with no members would be invisible to the person who made it.
                .andExpect(jsonPath("$.yourRole").value("ADMIN"))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].name").value("Alice"))
                // A member list is not an address book.
                .andExpect(jsonPath("$.members[0].email").doesNotExist())
                .andExpect(jsonPath("$.members[0].upiId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String groupId = JsonPath.read(created, "$.id");

        mockMvc.perform(get("/api/v1/groups").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].memberCount").value(1))
                .andExpect(jsonPath("$[0].yourRole").value("ADMIN"));

        mockMvc.perform(get("/api/v1/groups/" + groupId)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(groupId));
    }

    @Test
    void anotherUserGets404ForAGroupTheyAreNotIn() throws Exception {
        String alice = newUser("Alice");
        String bob = newUser("Bob");

        String groupId = JsonPath.read(mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice only\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        // 404, never 403. A 403 would confirm the group exists, which is exactly how an
        // attacker enumerates valid ids without belonging to anything.
        mockMvc.perform(get("/api/v1/groups/" + groupId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // And it does not appear in his list either.
        mockMvc.perform(get("/api/v1/groups").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aGroupIdThatDoesNotExistIsIndistinguishableFromOneYouCannotSee() throws Exception {
        String alice = newUser("Alice");

        mockMvc.perform(get("/api/v1/groups/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsAnyCurrencyOtherThanInr() throws Exception {
        String alice = newUser("Alice");

        mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nordic Trip\",\"currency\":\"USD\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_UNSUPPORTED"));
    }

    @Test
    void rejectsABlankNameAndRequiresAuthentication() throws Exception {
        String alice = newUser("Alice");

        mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/groups"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
