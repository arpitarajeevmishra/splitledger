package com.splitledger.payment.razorpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * A deployment with no Razorpay keys must start (SL-601, ADR-013).
 *
 * <p>This exists because it did not. {@code application.yml} defines
 * {@code key-id: ${RAZORPAY_KEY_ID:}}, so with no environment variable the property is
 * <em>present and empty</em> -- and {@code @ConditionalOnProperty} matches any present
 * value that is not "false". The provider was therefore built in every keyless context,
 * where its constructor refused to start the application. Every integration test in CI
 * failed, while every local run passed because a local {@code .env} always had keys.
 *
 * <p>Deliberately configures nothing. That is the whole test: the bean must be absent, and
 * the context must come up, when nobody has said anything about Razorpay at all. The rest
 * of the suite sets keys, so nothing else in it can ever notice this again.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.jwt.secret=a-no-razorpay-secret-long-enough-for-hs256")
class RazorpayNotConfiguredIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void theProviderIsNotEvenCreated() {
        assertThat(context.getBeansOfType(RazorpayProvider.class))
                .as("no keys, no gateway provider -- the sandbox answers instead (ADR-013)")
                .isEmpty();
    }

    @Test
    void andTheSandboxIsStillThereToAnswer() {
        // The point of the demo posture: the settlement path has to work with no account,
        // no key and no network call at all.
        assertThat(context.getBeansOfType(com.splitledger.payment.port.PaymentProvider.class)
                .values())
                .anySatisfy(provider -> assertThat(provider.method())
                        .isEqualTo(com.splitledger.common.payment.PaymentMethod.SANDBOX));
    }
}
