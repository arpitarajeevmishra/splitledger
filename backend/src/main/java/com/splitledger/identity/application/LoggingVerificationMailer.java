package com.splitledger.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Records that a link was issued, and -- only when asked -- what it was (SL-901).
 *
 * <p><b>The token is not logged by default, and that is the whole design of this class.</b>
 * A verification link in a log file is a credential in a log file: logs are shipped,
 * aggregated, retained and read by more people than the mailbox was. Logging it "just for
 * now" is how an account-takeover primitive ends up in a retention bucket.
 *
 * <p>With {@code app.verification.log-links=true} it logs the token, for local development
 * where there is no mailbox to read and the database is a throwaway. It is off in every
 * shipped configuration, and turning it on in production would hand anyone with log access
 * the ability to verify as any account that registers.
 */
@Component
class LoggingVerificationMailer implements VerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationMailer.class);

    private final boolean logLinks;

    LoggingVerificationMailer(@Value("${app.verification.log-links:false}") boolean logLinks) {
        this.logLinks = logLinks;
    }

    @Override
    public void send(String email, String rawToken) {
        if (logLinks) {
            log.warn("Verification token for {}: {} (app.verification.log-links is on; this "
                    + "must never be set in a deployed environment)", email, rawToken);
            return;
        }
        // The address and not the token. Enough to answer "was one issued at all", which is
        // the question an operator actually has, and nothing anybody can redeem.
        log.info("Issued a verification link for {} (no mailer configured yet -- SL-902)",
                email);
    }
}
