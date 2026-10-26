package com.splitledger.identity.application;

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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proving that an account can read the address it claims (SL-901).
 *
 * <p>The hole: an invite is addressed to an email, and accepting one checks that the
 * caller's email matches -- but nothing checked that the caller <em>owns</em> it. Register
 * as someone else's address, accept their invitation, read that group's members, expenses
 * and balances. Registration has been open on the deployed demo with that gap in place.
 */
@Service
public class EmailVerificationService {

    private final UserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final VerificationMailer mailer;
    private final Duration ttl;
    private final int resendsPerHour;
    private final Clock clock;

    @Autowired
    EmailVerificationService(UserRepository users, EmailVerificationTokenRepository tokens,
            VerificationMailer mailer,
            @Value("${app.verification.ttl}") Duration ttl,
            @Value("${app.verification.resends-per-hour}") int resendsPerHour) {
        this(users, tokens, mailer, ttl, resendsPerHour, Clock.systemUTC());
    }

    /** Visible for testing. */
    EmailVerificationService(UserRepository users, EmailVerificationTokenRepository tokens,
            VerificationMailer mailer, Duration ttl, int resendsPerHour, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.mailer = mailer;
        this.ttl = ttl;
        this.resendsPerHour = resendsPerHour;
        this.clock = clock;
    }

    /**
     * Issue a link, as part of registering.
     *
     * <p>Not rate-limited here: registration already is, and this happens once inside it.
     * A second limit on the same path would only be able to fail a registration that the
     * first one let through.
     */
    @Transactional
    public void issue(UUID userId, String email) {
        send(userId, email, Instant.now(clock));
    }

    /**
     * Send another one.
     *
     * <p>Capped per account against the database rather than in an in-process bucket,
     * because the thing being protected is somebody else's inbox and an in-process counter
     * resets on every deploy and does not exist on a second instance. The cap is low: a
     * person who has not received one of three links has a delivery problem that a fourth
     * will not solve.
     */
    @Transactional
    public void resend(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Unknown user"));
        if (user.isEmailVerified()) {
            // Not an error. Asking twice is what a person does when they are unsure it
            // worked, and refusing would mean explaining a state they cannot act on.
            return;
        }

        Instant now = Instant.now(clock);
        if (tokens.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofHours(1)))
                >= resendsPerHour) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "Too many verification emails requested; try again later");
        }
        send(userId, user.email(), now);
    }

    /**
     * Redeem a link.
     *
     * <p>Unknown, already used and expired all answer the same way, for the same reason the
     * invite endpoints do: telling a stranger holding a guessed token which of those it was
     * confirms that it existed.
     */
    @Transactional
    public void verify(String rawToken) {
        Instant now = Instant.now(clock);
        EmailVerificationToken token = tokens.findByTokenHash(SecureTokens.hash(rawToken))
                .filter(candidate -> candidate.isUsable(now))
                .orElseThrow(() -> new ApiException(ErrorCode.VERIFICATION_INVALID,
                        "Unknown, used or expired verification link"));

        // Stamped before the user is changed, so a failure to load the user cannot leave a
        // token that was accepted and can be presented again.
        token.use(now);
        tokens.save(token);

        User user = users.findById(token.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.VERIFICATION_INVALID,
                        "Unknown, used or expired verification link"));
        user.verifyEmail(now);
        users.save(user);
    }

    private void send(UUID userId, String email, Instant now) {
        String rawToken = SecureTokens.random();
        tokens.save(EmailVerificationToken.issue(userId, SecureTokens.hash(rawToken), now,
                now.plus(ttl)));
        // After the row, so a token can never be in flight without something to match it
        // against -- and the raw value exists only in this call and the message.
        mailer.send(email, rawToken);
    }
}
