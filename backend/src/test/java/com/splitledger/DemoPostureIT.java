package com.splitledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.splitledger.common.limits.AccountLimits;
import com.splitledger.common.money.SplitLimits;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What this application does when nobody has configured it (SL-906).
 *
 * <p>The one test class that deliberately overrides almost nothing. Every other
 * integration test sets {@code app.registration-enabled=true} because it needs accounts,
 * which means every other test proves the opposite of the thing that matters here: that an
 * unconfigured deployment is closed. A default is only a default if something checks it.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.jwt.secret=a-demo-posture-secret-long-enough-for-hs256")
class DemoPostureIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountLimits limits;

    @Test
    void registrationIsClosedUntilSomebodyOpensIt() throws Exception {
        // SL-901 ships the switch on, together with email verification. Until then a
        // stranger who finds the URL cannot create an account, which is the whole of the
        // S6 "deploy it with the door shut" posture (ADR-013).
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"stranger@example.com\",\"name\":\"Stranger\","
                                + "\"password\":\"password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGISTRATION_DISABLED"));
    }

    @Test
    void theConfigEndpointTellsTheUiTheDoorIsShut() throws Exception {
        // The sign-up page reads this before anyone has an account to ask with (SL-903).
        // If it ever disagreed with the endpoint above, the UI would invite people through
        // a door the server keeps locked.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"stranger@example.com\",\"name\":\"Stranger\","
                                + "\"password\":\"password123\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationEnabled").value(false))
                .andExpect(jsonPath("$.upiIntentEnabled").value(false))
                .andExpect(jsonPath("$.demoMode").value(true))
                // No keys here, so no card payment is offered. The UI reads this one
                // field: a key plus a separate "enabled" flag could disagree, and the
                // disagreement would offer a payment the server cannot take (SL-605).
                .andExpect(jsonPath("$.razorpayKeyId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void theShippedMemberCapMatchesWhatAnExpenseCanBeSplitBetween() {
        // A group may not hold more people than one expense can include, or the product
        // allows a group whose own whole-group split is impossible. These two numbers live
        // in different files and have to agree; this is the only thing that notices.
        assertThat(limits.membersPerGroup()).isEqualTo(SplitLimits.MAX_PARTICIPANTS);
    }

    @Test
    void theShippedCapsAreLoadedAndNotZero() {
        // Guards against the caps quietly becoming absent: an empty app.limits block would
        // bind zeroes, and a zero cap refuses the first request rather than the abusive one.
        assertThat(limits.groupsPerAccount()).isPositive();
        assertThat(limits.expensesPerDay()).isPositive();
    }
}
