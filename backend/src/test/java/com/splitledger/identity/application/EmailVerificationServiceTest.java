package com.splitledger.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.security.SecureTokens;
import com.splitledger.identity.domain.EmailVerificationToken;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.EmailVerificationTokenRepository;
import com.splitledger.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Proving an address, and the three properties that make the proof worth anything (SL-901):
 * the raw token is never stored, a link works once, and it stops working.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);
    private static final String EMAIL = "someone@example.com";

    @Mock
    private UserRepository users;
    @Mock
    private EmailVerificationTokenRepository tokens;
    @Mock
    private VerificationMailer mailer;

    private EmailVerificationService service() {
        return service(NOW);
    }

    private EmailVerificationService service(Instant now) {
        return new EmailVerificationService(users, tokens, mailer, TTL, 3,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static User account() {
        return User.register(EMAIL, "Someone", "hash", Instant.parse("2026-10-26T11:00:00Z"));
    }

    private String issueAndCaptureRawToken(UUID userId) {
        service().issue(userId, EMAIL);
        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(EMAIL), raw.capture());
        return raw.getValue();
    }

    private EmailVerificationToken savedToken() {
        ArgumentCaptor<EmailVerificationToken> saved =
                ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokens).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void stores_only_the_hash_of_the_token_it_sends() {
        UUID userId = UUID.randomUUID();
        String rawToken = issueAndCaptureRawToken(userId);

        // The whole reason a database leak is survivable. If the stored value were the
        // token, anybody who read this table could verify as any account in it.
        EmailVerificationToken stored = savedToken();
        assertThat(rawToken).isNotBlank();
        assertThat(stored.userId()).isEqualTo(userId);
        assertThat(storedHashOf(stored)).isEqualTo(SecureTokens.hash(rawToken))
                .isNotEqualTo(rawToken);
    }

    @Test
    void the_link_expires() {
        UUID userId = UUID.randomUUID();
        issueAndCaptureRawToken(userId);

        assertThat(savedToken().expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void verifying_marks_the_token_used_and_the_account_verified() {
        UUID userId = UUID.randomUUID();
        String rawToken = "a-raw-token";
        EmailVerificationToken token = EmailVerificationToken.issue(userId,
                SecureTokens.hash(rawToken), NOW.minus(Duration.ofMinutes(5)), NOW.plus(TTL));
        User user = account();
        when(tokens.findByTokenHash(SecureTokens.hash(rawToken))).thenReturn(Optional.of(token));
        when(users.findById(userId)).thenReturn(Optional.of(user));

        service().verify(rawToken);

        assertThat(token.usedAt()).isEqualTo(NOW);
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    void a_link_works_once() {
        UUID userId = UUID.randomUUID();
        String rawToken = "a-raw-token";
        EmailVerificationToken token = EmailVerificationToken.issue(userId,
                SecureTokens.hash(rawToken), NOW.minus(Duration.ofMinutes(5)), NOW.plus(TTL));
        token.use(NOW.minus(Duration.ofMinutes(1)));
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        // The account must exist, or this passes because the user lookup failed rather
        // than because the token was refused -- which is how the first version of this
        // test survived an implementation that ignored usedAt entirely.
        when(users.findById(userId)).thenReturn(Optional.of(account()));

        // Single use bounds what a link already used is worth to whoever finds it later --
        // in a forwarded email, a shared screenshot, a browser left open.
        assertThatThrownBy(() -> service().verify(rawToken))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> assertThat(((ApiException) failure).code())
                        .isEqualTo(ErrorCode.VERIFICATION_INVALID));
    }

    @Test
    void an_expired_link_is_refused() {
        UUID userId = UUID.randomUUID();
        String rawToken = "a-raw-token";
        EmailVerificationToken token = EmailVerificationToken.issue(userId,
                SecureTokens.hash(rawToken), NOW.minus(Duration.ofDays(2)),
                NOW.minus(Duration.ofDays(1)));
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        User user = account();
        when(users.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().verify(rawToken)).isInstanceOf(ApiException.class);

        // Asserted as well as thrown: expiry bounds how long a link left sitting in an
        // inbox stays dangerous, so the account must be no more verified than before.
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void an_unknown_link_fails_the_same_way_a_used_one_does() {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        // Telling a stranger holding a guessed token whether it ever existed is a
        // confirmation they did not have; unknown, used and expired answer identically.
        assertThatThrownBy(() -> service().verify("never-issued"))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> assertThat(((ApiException) failure).code())
                        .isEqualTo(ErrorCode.VERIFICATION_INVALID));
    }

    @Test
    void resending_is_capped_per_account() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(account()));
        when(tokens.countByUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(3L);

        // The cap protects somebody else's mailbox, not this server: without it, anybody
        // can have mail posted to any address they can register, from a reputable sender,
        // as often as they like.
        assertThatThrownBy(() -> service().resend(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> assertThat(((ApiException) failure).code())
                        .isEqualTo(ErrorCode.RATE_LIMITED));
        verify(mailer, never()).send(anyString(), anyString());
    }

    @Test
    void resending_below_the_cap_sends_to_the_account_s_own_address() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(account()));
        when(tokens.countByUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(2L);

        service().resend(userId);

        // The account's address, never one from the request. An endpoint that took an
        // address would be a spam relay with extra steps.
        verify(mailer).send(eq(EMAIL), anyString());
    }

    @Test
    void resending_for_an_already_verified_account_does_nothing_and_is_not_an_error() {
        UUID userId = UUID.randomUUID();
        User user = account();
        user.verifyEmail(NOW.minus(Duration.ofDays(1)));
        when(users.findById(userId)).thenReturn(Optional.of(user));

        service().resend(userId);

        // Asking twice is what somebody does when unsure it worked. Refusing would mean
        // explaining a state they cannot act on, and it would burn a slot in the cap.
        verify(mailer, never()).send(anyString(), anyString());
        verify(tokens, never()).save(any());
    }

    /** Reaches the hash the way the repository would, since the entity does not expose it. */
    private static String storedHashOf(EmailVerificationToken token) {
        try {
            var field = EmailVerificationToken.class.getDeclaredField("tokenHash");
            field.setAccessible(true);
            return (String) field.get(token);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
