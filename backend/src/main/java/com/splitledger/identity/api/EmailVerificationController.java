package com.splitledger.identity.api;

import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.identity.application.EmailVerificationService;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redeeming and re-requesting a verification link (SL-901).
 */
@RestController
@RequestMapping("/api/v1/auth/verify-email")
public class EmailVerificationController {

    private final EmailVerificationService verification;

    EmailVerificationController(EmailVerificationService verification) {
        this.verification = verification;
    }

    public record VerifyRequest(@NotBlank String token) {
    }

    /**
     * Unauthenticated on purpose.
     *
     * <p>The link arrives in an email and is opened in whatever browser happens to be
     * there, usually not the one holding a session. Requiring a login first would mean the
     * common case is "click link, get a sign-in page, lose the link".
     *
     * <p>The token is the authentication: 256 bits from a CSPRNG, stored only as a hash,
     * single-use and short-lived.
     *
     * <p>In the body rather than the query string, because query strings are logged by
     * every proxy in the path, kept in browser history and sent in Referer headers. The
     * emailed URL carries it as a fragment and the page posts it here.
     */
    @PostMapping
    public Map<String, String> verify(@RequestBody VerifyRequest request) {
        verification.verify(request.token());
        return Map.of("status", "verified");
    }

    /**
     * Authenticated, and takes no address.
     *
     * <p>The account's own email is used, never one from the request: an endpoint that
     * accepted an address would send mail anywhere somebody typed, from a reputable
     * sender, which is a spam relay with extra steps.
     *
     * <p>Rate-limited inside the service, against the database rather than an in-process
     * bucket, because what is being protected is a mailbox rather than this server.
     */
    @PostMapping("/resend")
    public Map<String, String> resend(Authentication authentication) {
        verification.resend(AuthenticatedUser.require(authentication));
        return Map.of("status", "sent");
    }
}
