package com.splitledger.payment.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.splitledger.TestcontainersConfiguration;
import com.splitledger.expense.application.ExpenseService;
import com.splitledger.expense.domain.SplitType;
import com.splitledger.group.application.GroupMembershipService;
import com.splitledger.group.domain.Role;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Razorpay order path with the application wired as it would be in production
 * (SL-601), pointed at a stub standing in for api.razorpay.com.
 *
 * <p>A stub rather than mocked collaborators, because what is under test here is the
 * wiring and not the provider: that keys present make the provider a bean at all, that
 * {@code RAZORPAY} resolves to it rather than to the sandbox, that the order id it returns
 * is persisted, and that asking twice does not create a second order. None of those live
 * in a class that a unit test could reach -- the only honest way to check them is to run
 * the application and count what arrived at the gateway.
 *
 * <p>{@link RazorpayProviderTest} covers the request and the failure modes; this covers
 * everything around it.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-razorpay-order-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000",
        // A test-mode shaped pair. The provider is only a bean because these are here,
        // which is itself one of the things this asserts.
        "app.payments.razorpay.key-id=rzp_test_wired",
        "app.payments.razorpay.key-secret=stub-secret"
})
class RazorpayOrderIT {

    /** Every order the stub was asked to create, in arrival order. */
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    private static final HttpServer STUB = startStub();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

    @Autowired
    private ExpenseService expenses;

    @DynamicPropertySource
    static void pointTheProviderAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("app.payments.razorpay.base-url",
                () -> "http://localhost:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/orders", exchange -> {
                RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                // A different id per call, so "the same order came back" cannot pass by
                // the stub happening to be constant.
                byte[] body = ("{\"id\":\"order_stub_" + RECEIVED.size()
                        + "\",\"status\":\"created\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("could not start the Razorpay stub", ex);
        }
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void forgetEarlierCalls() {
        RECEIVED.clear();
    }

    @Test
    void anOrderIsCreatedOnceAndTheSecondAskReturnsIt() throws Exception {
        Settle settle = aDebtOf(45_000L);

        String first = mockMvc.perform(post("/api/v1/settlements/" + settle.settlementId()
                        + "/gateway-order")
                        .header("Authorization", "Bearer " + settle.payerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.gatewayOrderId").value("order_stub_1"))
                .andReturn().getResponse().getContentAsString();

        // Asked again, as a client retrying after a dropped response would. Two orders
        // against one debt is two payments the payer did not intend to make (LLD 8).
        mockMvc.perform(post("/api/v1/settlements/" + settle.settlementId() + "/gateway-order")
                        .header("Authorization", "Bearer " + settle.payerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatewayOrderId")
                        .value(JsonPath.read(first, "$.gatewayOrderId")));

        assertThat(RECEIVED)
                .as("Razorpay was asked exactly once, however often the client asked us")
                .hasSize(1);
        // And the request carried our settlement id, which is the only thing a later
        // callback can be matched on.
        assertThat(RECEIVED.getFirst())
                .contains("\"receipt\":\"" + settle.settlementId() + "\"")
                .contains("\"amount\":45000");
    }

    @Test
    void razorpayAnswersForRazorpayRatherThanTheSandbox() throws Exception {
        Settle settle = aDebtOf(45_000L);

        mockMvc.perform(post("/api/v1/settlements/" + settle.settlementId() + "/gateway-order")
                        .header("Authorization", "Bearer " + settle.payerToken()))
                .andExpect(status().isOk())
                // The sandbox makes ids like sandbox_<uuid>. Reaching the stub at all is
                // the proof that method selection, not bean ordering, decided this.
                .andExpect(jsonPath("$.gatewayOrderId").value("order_stub_1"));

        assertThat(RECEIVED).hasSize(1);
    }

    @Test
    void aSettlementUnderARupeeNeverReachesTheGateway() throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expenses.record(groupId, alice.id(), "Dinner", 20_000L, alice.id(),
                LocalDate.of(2026, 1, 10), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(alice.id(), null),
                        new ExpenseService.Participant(bob.id(), null)));

        // Refused at creation, by us, with our code -- not by an opaque gateway failure
        // two steps later (finding C5).
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                        .header("Authorization", "Bearer " + bob.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUser\":\"" + alice.id()
                                + "\",\"amountPaise\":40,\"method\":\"RAZORPAY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_BELOW_GATEWAY_MINIMUM"));

        assertThat(RECEIVED).as("nothing left the building").isEmpty();
    }

    private record Account(String token, UUID id) {
    }

    private record Settle(UUID settlementId, String payerToken) {
    }

    /** Bob owes Alice half of an expense, and a RAZORPAY settlement for it. */
    private Settle aDebtOf(long amountPaise) throws Exception {
        Account alice = newUser("Alice");
        Account bob = newUser("Bob");
        UUID groupId = newGroup(alice);
        membership.joinOrRejoin(groupId, bob.id(), Role.MEMBER);
        expenses.record(groupId, alice.id(), "Dinner", amountPaise * 2, alice.id(),
                LocalDate.of(2026, 1, 10), SplitType.EQUAL,
                List.of(new ExpenseService.Participant(alice.id(), null),
                        new ExpenseService.Participant(bob.id(), null)));

        String created = mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                        .header("Authorization", "Bearer " + bob.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUser\":\"" + alice.id() + "\",\"amountPaise\":"
                                + amountPaise + ",\"method\":\"RAZORPAY\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return new Settle(UUID.fromString(JsonPath.read(created, "$.id")), bob.token());
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
