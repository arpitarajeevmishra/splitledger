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
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.ledger.domain.LedgerPosting;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
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
 * The webhook, raced (SL-603, finding B6).
 *
 * <p>The acceptance criterion is the whole point of this class: twenty identical
 * concurrent deliveries must produce exactly one ledger transaction. Razorpay retries
 * until it gets a 2xx, and a retry that arrives while the first is still in flight is the
 * case that a read-then-write duplicate check would get wrong.
 *
 * <p>So the threads start on a barrier and race the real database, as ConcurrencyIT does.
 * Nineteen of them block on the claim row until the winner commits -- which is the
 * behaviour being tested, not an accident of timing.
 */
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-razorpay-webhook-secret-long-enough-for-hs256",
        "app.rate-limit.limits.login.capacity=1000",
        "app.rate-limit.limits.register.capacity=1000",
        "app.rate-limit.limits.invite-create.capacity=1000",
        "app.rate-limit.limits.invite-lookup.capacity=1000",
        "app.payments.razorpay.key-id=rzp_test_wired",
        "app.payments.razorpay.key-secret=stub-secret",
        "app.payments.razorpay.webhook-secret=" + RazorpayWebhookIT.WEBHOOK_SECRET
})
class RazorpayWebhookIT {

    static final String WEBHOOK_SECRET = "a-webhook-secret-for-the-it";

    private static final HttpServer STUB = startStub();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupMembershipService membership;

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private LedgerService ledger;

    @DynamicPropertySource
    static void pointTheProviderAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("app.payments.razorpay.base-url",
                () -> "http://localhost:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/orders", exchange -> {
                exchange.getRequestBody().readAllBytes();
                // Unique, as a real gateway's ids are: the settlements table has
                // UNIQUE (gateway_order_id), so a counter would collide between tests.
                byte[] body = ("{\"id\":\"order_" + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 14) + "\",\"status\":\"created\"}")
                        .getBytes(StandardCharsets.UTF_8);
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

    /** The criterion, raced. */
    @Test
    void twentyIdenticalConcurrentDeliveriesPostExactlyOnce() throws Exception {
        Settle settle = aDebtOf(45_000L);
        String orderId = order(settle);
        String body = capturedBody(orderId, "pay_raced");
        String signature = sign(body, WEBHOOK_SECRET);
        String eventId = "evt_raced_" + UUID.randomUUID();

        int deliveries = 20;
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(deliveries);

        try (ExecutorService pool = Executors.newFixedThreadPool(deliveries)) {
            List<Future<?>> all = new java.util.ArrayList<>();
            for (int i = 0; i < deliveries; i++) {
                all.add(pool.submit(() -> {
                    start.await();
                    String outcome = JsonPath.read(mockMvc.perform(
                                    post("/webhooks/razorpay")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .header("X-Razorpay-Signature", signature)
                                            .header("X-Razorpay-Event-Id", eventId)
                                            .content(body))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString(), "$.status");
                    if ("processed".equals(outcome)) {
                        processed.incrementAndGet();
                    } else if ("duplicate".equals(outcome)) {
                        duplicates.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : all) {
                future.get();
            }
        }

        assertThat(processed.get()).as("exactly one delivery did the work").isEqualTo(1);
        assertThat(duplicates.get()).as("the other nineteen recognised a repeat")
                .isEqualTo(deliveries - 1);

        // The claim is a mechanism; the ledger is the thing that must not move twice. One
        // transaction for the expense, one for the settlement, and the payer back to zero.
        assertThat(distinctTransactions(settle)).as("one expense, one settlement, no more")
                .isEqualTo(2);
        assertThat(netFor(settle)).as("the debt is settled once").isZero();
    }

    @Test
    void aTamperedDeliveryIsRefusedAndChangesNothing() throws Exception {
        Settle settle = aDebtOf(45_000L);
        String orderId = order(settle);
        String body = capturedBody(orderId, "pay_tampered");
        String genuine = sign(body, WEBHOOK_SECRET);
        String tampered = (genuine.charAt(0) == 'a' ? 'b' : 'a') + genuine.substring(1);

        mockMvc.perform(post("/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", tampered)
                        .header("X-Razorpay-Event-Id", "evt_tampered")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_SIGNATURE_INVALID"));

        assertThat(distinctTransactions(settle)).as("only the expense").isEqualTo(1);
        assertThat(netFor(settle)).as("the debt is untouched").isEqualTo(-45_000L);
    }

    @Test
    void aBodyEditedAfterSigningIsRefused() throws Exception {
        // A genuine delivery for one order, re-aimed at another. This is the attack the
        // raw-body signature exists to stop, and it is worth racing end to end rather than
        // only in a unit test.
        Settle mine = aDebtOf(45_000L);
        String mineOrder = order(mine);
        Settle theirs = aDebtOf(30_000L);
        String theirOrder = order(theirs);

        String theirBody = capturedBody(theirOrder, "pay_theirs");
        String theirSignature = sign(theirBody, WEBHOOK_SECRET);
        String reaimed = theirBody.replace(theirOrder, mineOrder);

        mockMvc.perform(post("/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", theirSignature)
                        .header("X-Razorpay-Event-Id", "evt_reaimed")
                        .content(reaimed))
                .andExpect(status().isBadRequest());

        assertThat(netFor(mine)).isEqualTo(-75_000L);
    }

    @Test
    void anEventAboutAnOrderNobodyHoldsIsAcceptedAndIgnored() throws Exception {
        // Verified, and about an order this application has no settlement for -- an orphan
        // from a failed transaction (O-13), or another deployment on the same account. It
        // must be a 200, or Razorpay redelivers it forever.
        String body = capturedBody("order_nobodyHasThis", "pay_orphan");

        mockMvc.perform(post("/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", sign(body, WEBHOOK_SECRET))
                        .header("X-Razorpay-Event-Id", "evt_orphan_" + UUID.randomUUID())
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
    }

    @Test
    void anAuthorisedButUncapturedEventSettlesNothing() throws Exception {
        // The money is reserved, not taken. Confirming here would settle a debt against
        // funds that can still be released.
        Settle settle = aDebtOf(45_000L);
        String orderId = order(settle);
        String body = capturedBody(orderId, "pay_authorised")
                .replace("payment.captured", "payment.authorized");

        mockMvc.perform(post("/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", sign(body, WEBHOOK_SECRET))
                        .header("X-Razorpay-Event-Id", "evt_auth_" + UUID.randomUUID())
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        assertThat(netFor(settle)).isEqualTo(-45_000L);
    }

    private static String capturedBody(String orderId, String paymentId) {
        return "{\"entity\":\"event\",\"event\":\"payment.captured\",\"contains\":[\"payment\"],"
                + "\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"amount\":45000,\"currency\":\"INR\",\"status\":\"captured\","
                + "\"order_id\":\"" + orderId + "\"}}},\"created_at\":1700000000}";
    }

    private static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long netFor(Settle settle) {
        return ledger.postingsFor(settle.groupId()).stream()
                .filter(posting -> posting.userId().equals(settle.payerId()))
                .mapToLong(posting -> posting.amount().paise())
                .sum();
    }

    private int distinctTransactions(Settle settle) {
        return ledger.postingsFor(settle.groupId()).stream()
                .map(LedgerPosting::txnId)
                .collect(Collectors.toSet())
                .size();
    }

    private String order(Settle settle) throws Exception {
        return JsonPath.read(mockMvc.perform(
                        post("/api/v1/settlements/" + settle.settlementId() + "/gateway-order")
                                .header("Authorization", "Bearer " + settle.payerToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.gatewayOrderId");
    }

    private record Account(String token, UUID id) {
    }

    private record Settle(UUID settlementId, String payerToken, UUID groupId, UUID payerId) {
    }

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

        return new Settle(UUID.fromString(JsonPath.read(created, "$.id")), bob.token(),
                groupId, bob.id());
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
