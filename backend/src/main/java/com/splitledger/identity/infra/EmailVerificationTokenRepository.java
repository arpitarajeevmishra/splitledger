package com.splitledger.identity.infra;

import com.splitledger.identity.domain.EmailVerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * How many links this account has been sent lately.
     *
     * <p>The resend limit is counted per account rather than per IP, because the thing
     * being protected is somebody else's inbox: an uncapped resend makes this application
     * a way to post mail to any address a stranger types, from a reputable sender, as
     * often as they like.
     */
    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);
}
