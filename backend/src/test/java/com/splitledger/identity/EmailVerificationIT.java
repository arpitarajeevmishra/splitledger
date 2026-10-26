package com.splitledger.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.splitledger.TestcontainersConfiguration;
import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.group.application.InviteService;
import com.splitledger.identity.application.AuthService;
import com.splitledger.identity.application.EmailVerificationService;
import com.splitledger.identity.application.VerificationMailer;
import com.splitledger.identity.infra.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The hole, and that it is shut (SL-901).
 *
 * <p>End to end against a real database because the pieces are spread across three modules:
 * registration issues the token, the invite path asks whether the address was proven, and
 * the answer travels over a port between `identity` and `group`. Each piece has unit tests;
 * none of them can show that the wiring between them is right.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.registration-enabled=true",
        "app.jwt.secret=a-verification-test-secret-long-enough-for-hs256",
        "app.rate-limit.limits.register.capacity=1000"
})
class EmailVerificationIT {

    /**
     * Mocked for one reason: the raw token exists only in this call and is never stored,
     * so capturing it here is the only way a test can hold the link a person would click.
     */
    @MockitoBean
    private VerificationMailer mailer;

    @Autowired
    private AuthService auth;

    @Autowired
    private EmailVerificationService verification;

    @Autowired
    private InviteService invites;

    @Autowired
    private UserRepository users;

    private record Registered(UUID id, String email, String rawToken) {
    }

    private Registered register(String name) {
        String email = name + "-" + System.nanoTime() + "@example.com";
        AuthService.Session session = auth.register(email, name, "a-strong-password-1");
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(com.splitledger.identity.domain.User.normaliseEmail(email)),
                raw.capture());
        org.mockito.Mockito.clearInvocations(mailer);
        return new Registered(session.user().id(), email, raw.getValue());
    }

    @Test
    void registering_issues_a_link_and_leaves_the_account_unverified() {
        Registered registered = register("alice");

        assertThat(registered.rawToken()).isNotBlank();
        // Unverified, even though they just proved they control the password. Controlling
        // the account is not the same as controlling the mailbox, which is the entire
        // distinction this story exists to draw.
        assertThat(users.findById(registered.id()).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void the_link_verifies_the_account_it_was_issued_for() {
        Registered registered = register("bob");

        verification.verify(registered.rawToken());

        assertThat(users.findById(registered.id()).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void one_account_s_link_does_not_verify_another() {
        Registered first = register("cara");
        Registered second = register("dev");

        verification.verify(first.rawToken());

        // Obvious, and worth pinning: the token carries the user id, and a lookup that
        // ignored it would verify whoever happened to be found first.
        assertThat(users.findById(first.id()).orElseThrow().isEmailVerified()).isTrue();
        assertThat(users.findById(second.id()).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void a_link_cannot_be_redeemed_twice() {
        Registered registered = register("erin");
        verification.verify(registered.rawToken());

        assertThatThrownBy(() -> verification.verify(registered.rawToken()))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.VERIFICATION_INVALID);
    }

    @Test
    void resending_produces_a_link_that_also_works() {
        Registered registered = register("fred");

        verification.resend(registered.id());
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(anyString(), raw.capture());
        verification.verify(raw.getValue());

        // The second link has to work on its own: somebody who asks for another one is
        // usually somebody for whom the first never arrived.
        assertThat(users.findById(registered.id()).orElseThrow().isEmailVerified()).isTrue();
    }
}
