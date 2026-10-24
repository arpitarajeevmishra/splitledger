package com.splitledger.payment.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.payment.port.PaymentRequest;
import com.splitledger.payment.port.ProviderOrder;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The Razorpay Orders call, against a stub that asserts the request we actually send.
 *
 * <p>The request matters as much as the response. "It returned an order id" is satisfied by
 * a call that sent the wrong amount, the wrong currency or no receipt -- and a wrong receipt
 * is a payment that can never be matched back to the settlement it paid.
 */
class RazorpayProviderTest {

    private static final UUID SETTLEMENT = UUID.fromString("2f1c7e2a-9b4d-4a11-8e3f-0a7b6c5d4e3f");

    private MockRestServiceServer server;

    private RazorpayProvider provider() {
        return provider(new RazorpayProperties("rzp_test_key", "secret", "a-webhook-secret", "https://stub.invalid",
                Duration.ofSeconds(2), Duration.ofSeconds(10)));
    }

    private RazorpayProvider provider(RazorpayProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new RazorpayProvider(properties, builder, new ObjectMapper());
    }

    @Test
    void sendsTheOrderRazorpaysApiExpectsAndReturnsItsId() {
        RazorpayProvider razorpay = provider();
        server.expect(requestTo("https://stub.invalid/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                // Basic auth with the key id as the user and the secret as the password.
                .andExpect(header("Authorization",
                        "Basic " + Base64.getEncoder()
                                .encodeToString("rzp_test_key:secret".getBytes())))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Sent with a length rather than streamed: see the comment in the provider.
                .andExpect(header("Content-Length", Matchers.notNullValue()))
                // Paise, not rupees. Sending 45000 as rupees would charge 100 times the debt.
                .andExpect(jsonPath("$.amount").value(45_000))
                .andExpect(jsonPath("$.currency").value("INR"))
                // The receipt is the only thing tying a later callback to what it settles.
                .andExpect(jsonPath("$.receipt").value(SETTLEMENT.toString()))
                // Without capture the money is authorised and never taken: the settlement
                // waits forever for funds that are reserved and then released.
                .andExpect(jsonPath("$.payment_capture").value(1))
                .andRespond(withSuccess(
                        "{\"id\":\"order_MkL9xQ2p\",\"entity\":\"order\",\"amount\":45000,"
                                + "\"currency\":\"INR\",\"status\":\"created\","
                                + "\"receipt\":\"" + SETTLEMENT + "\"}",
                        MediaType.APPLICATION_JSON));

        ProviderOrder order = razorpay.initiate(new PaymentRequest(SETTLEMENT, 45_000L, "INR"));

        assertThat(order.orderId()).isEqualTo("order_MkL9xQ2p");
        // Checkout is opened in the browser with this id; there is nowhere to redirect to.
        assertThat(order.payUrl()).isNull();
        server.verify();
    }

    @Test
    void ignoresTheFieldsRazorpayAddsAndWeDoNotRead() {
        // Their response carries a dozen fields and will grow. Binding all of them makes a
        // new field in someone else's API a failure in ours.
        RazorpayProvider razorpay = provider();
        server.expect(requestTo("https://stub.invalid/v1/orders"))
                .andRespond(withSuccess(
                        "{\"id\":\"order_new\",\"status\":\"created\",\"offer_id\":null,"
                                + "\"attempts\":0,\"notes\":{\"anything\":\"at all\"},"
                                + "\"a_field_invented_next_year\":true}",
                        MediaType.APPLICATION_JSON));

        assertThat(razorpay.initiate(new PaymentRequest(SETTLEMENT, 45_000L, "INR")).orderId())
                .isEqualTo("order_new");
    }

    @Test
    void refusesUnderARupeeWithoutCallingRazorpay() {
        RazorpayProvider razorpay = provider();
        // No expectation registered: verify() below fails if any call is made at all.

        assertThatThrownBy(() -> razorpay.initiate(new PaymentRequest(SETTLEMENT, 40L, "INR")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.SETTLEMENT_BELOW_GATEWAY_MINIMUM));

        server.verify();
    }

    @Test
    void aRefusedOrderBecomesOurGatewayErrorAndSaysNothingAboutTheKey() {
        RazorpayProvider razorpay = provider();
        server.expect(requestTo("https://stub.invalid/v1/orders"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(
                        "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\","
                                + "\"description\":\"Authentication failed for key rzp_test_key\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> razorpay.initiate(new PaymentRequest(SETTLEMENT, 45_000L, "INR")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.GATEWAY_ERROR))
                // Their body names the key. Ours must not repeat it to whoever is paying.
                .hasMessageNotContainingAny("rzp_test_key", "Authentication failed");
    }

    @Test
    void aGatewayOutageBecomesOurGatewayError() {
        RazorpayProvider razorpay = provider();
        server.expect(requestTo("https://stub.invalid/v1/orders")).andRespond(withServerError());

        assertThatThrownBy(() -> razorpay.initiate(new PaymentRequest(SETTLEMENT, 45_000L, "INR")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.GATEWAY_ERROR));
    }

    @Test
    void a200WithNoOrderIdIsNotSuccess() {
        // Storing an empty order reference loses the settlement: no callback could ever be
        // matched back to it, and the debt would sit PENDING until it expired.
        RazorpayProvider razorpay = provider();
        server.expect(requestTo("https://stub.invalid/v1/orders"))
                .andRespond(withSuccess("{\"status\":\"created\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> razorpay.initiate(new PaymentRequest(SETTLEMENT, 45_000L, "INR")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.GATEWAY_ERROR));
    }

    @Test
    void answersForRazorpayAndNothingElse() {
        assertThat(provider().method()).isEqualTo(PaymentMethod.RAZORPAY);
    }

    @Test
    void refusesToStartWithHalfAKeyPair() {
        // @ConditionalOnProperty only sees key-id, so a secret left blank would otherwise
        // produce a provider that authenticates as nobody and 502s on the first payment.
        assertThatThrownBy(() -> provider(new RazorpayProperties("rzp_test_key", "  ", null, null,
                null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key-secret");
    }

    @Test
    void trimsATrailingSlashSoTheOrdersPathIsNotDoubled() {
        // baseUrl + "/v1/orders" with a trailing slash gives //v1/orders, which Razorpay
        // answers with a 404 that looks like a missing endpoint rather than a typo.
        assertThat(new RazorpayProperties("k", "s", null, "https://api.razorpay.com/", null, null)
                .baseUrl()).isEqualTo("https://api.razorpay.com");
    }

    @Test
    void defaultsTheEndpointAndTheTimeoutsRatherThanLeavingThemNull() {
        RazorpayProperties defaults = new RazorpayProperties("k", "s", null, null, null, null);
        assertThat(defaults.baseUrl()).isEqualTo("https://api.razorpay.com");
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
