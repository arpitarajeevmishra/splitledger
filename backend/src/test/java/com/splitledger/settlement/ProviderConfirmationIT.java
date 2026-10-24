package com.splitledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.settlement.application.SettlementService;
import com.splitledger.settlement.domain.Settlement;
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
 * What a provider confirmation may do when the debt has moved under it (SL-604, ADR-010 as
 * narrowed).
 *
 * <p>One rule, two halves, and the second half is why the rule is phrased in terms of
 * {@code movesRealMoney} rather than {@code isGateway}:
 *
 * <ul>
 *   <li>a real gateway has already taken the money, so its report is recorded whatever the
 *       balance now says -- refusing it loses a payment that happened;</li>
 *   <li>the sandbox has taken nothing, so its report is evidence of nothing and stays
 *       subject to the ordinary net-balance check. A demo visibly in credit for money that
 *       never existed is worse than a refused sandbox confirmation.</li>
 * </ul>
 *
 * <p>Sandbox auto-confirm is off here: with it on, its own scheduler would confirm the
 * settlement this test is about before the test got to it.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-provider-confirmation-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000",
        "app.payments.sandbox.auto-confirm-enabled=false",
        // A key pair so the Razorpay provider exists; its orders go to the stub below.
        "app.payments.razorpay.key-id=rzp_test_confirmation",
        "app.payments.razorpay.key-secret=stub-secret"
})
class ProviderConfirmationIT {

    private static final com.splitledger.payment.razorpay.GatewayStub STUB =
            new com.splitledger.payment.razorpay.GatewayStub();

    @org.springframework.test.context.DynamicPropertySource
    static void pointTheProviderAtTheStub(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.payments.razorpay.base-url", STUB::baseUrl);
    }

    @org.junit.jupiter.api.AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private SettlementService settlements;

    @Autowired
    private LedgerService ledger;

    private record Account(String token, UUID id) {
    }

    @Test
    void aRealGatewayPaymentIsRecordedEvenAfterTheDebtWasSettledElsewhere() throws Exception {
        Fixture f = aDebtOf(45_000L);
        Settlement card = settlements.create(f.groupId(), f.payer(), f.payee(), 45_000L,
                PaymentMethod.RAZORPAY);
        // Through the real order path, so the row is PENDING in the database. Calling
        // markPending on the returned entity would mutate an object nobody saves, and
        // confirmFromProvider re-reads the row -- which would still say INITIATED.
        settlements.createProviderOrder(card.id(), f.payer());
        settleInCash(f);
        assertThat(netFor(f)).as("the debt is gone before the capture arrives").isZero();

        settlements.confirmFromProvider(card.id(), "pay_real");

        // In credit, not refused: the money left the payer and the ledger says so.
        assertThat(netFor(f)).isEqualTo(45_000L);
    }

    @Test
    void aSandboxConfirmationPastTheDebtIsStillRefused() throws Exception {
        // Nothing moved, so there is no payment to lose -- and a demo visitor in credit for
        // money that never existed is the one thing this product cannot afford.
        Fixture f = aDebtOf(45_000L);
        Settlement sandbox = settlements.create(f.groupId(), f.payer(), f.payee(), 45_000L,
                PaymentMethod.SANDBOX);
        settlements.createProviderOrder(sandbox.id(), f.payer());
        settleInCash(f);
        assertThat(netFor(f)).isZero();

        assertThatThrownBy(() -> settlements.confirmFromProvider(sandbox.id(), "sandbox_payment_x"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.SETTLEMENT_EXCEEDS_OUTSTANDING));

        assertThat(netFor(f)).as("nothing moved, nothing posted").isZero();
    }

    /** Bob hands Alice cash for the whole debt, and she confirms it. */
    private void settleInCash(Fixture f) {
        Settlement cash = settlements.create(f.groupId(), f.payer(), f.payee(), 45_000L,
                PaymentMethod.CASH);
        settlements.markPaid(cash.id(), f.payer());
        settlements.confirm(cash.id(), f.payee());
    }

    private long netFor(Fixture f) {
        return ledger.postingsFor(f.groupId()).stream()
                .filter(posting -> posting.userId().equals(f.payer()))
                .mapToLong(posting -> posting.amount().paise())
                .sum();
    }

    private record Fixture(UUID groupId, UUID payer, UUID payee) {
    }

    private Fixture aDebtOf(long amountPaise) throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expenses.record(groupId, alice.id(), "Dinner", amountPaise * 2, alice.id(),
                LocalDate.of(2026, 1, 10), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(alice.id(), null),
                        new ExpenseService.Participant(bob.id(), null)));
        return new Fixture(groupId, bob.id(), alice.id());
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
}
