package com.splitledger.group.api;

import com.splitledger.common.ratelimit.RateLimiter;
import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.group.api.dto.InvitePreviewResponse;
import com.splitledger.group.application.InviteService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redeeming an invitation, addressed by its token rather than by group id -- the recipient
 * is not a member yet and cannot read anything under {@code /groups}.
 *
 * <p>The preview is public so the landing page can say what the invitation is for before
 * asking anyone to sign in. Accepting and declining are not: joining a group that records
 * who owes whom has to be an act of the invited person, so both require a session and
 * both check it against the invited address.
 */
@RestController
@RequestMapping("/api/v1/invites/{token}")
public class InviteRedemptionController {

    private final InviteService invites;
    private final RateLimiter rateLimiter;

    InviteRedemptionController(InviteService invites, RateLimiter rateLimiter) {
        this.invites = invites;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public InvitePreviewResponse preview(HttpServletRequest http, @PathVariable String token) {
        // Public and token-addressed, so this is the one invite endpoint someone could
        // grind against. 256 bits is not guessable, but a cap costs nothing and keeps a
        // scripted sweep from being free.
        rateLimiter.check("invite-lookup", clientIp(http));

        InviteService.Preview preview = invites.preview(token);
        return new InvitePreviewResponse(preview.groupName(), preview.email(),
                preview.expiresAt());
    }

    @PostMapping("/accept")
    public Map<String, UUID> accept(Authentication authentication, @PathVariable String token) {
        UUID groupId = invites.accept(token, AuthenticatedUser.require(authentication));
        // The group id is what the client needs next: it navigates straight there.
        return Map.of("groupId", groupId);
    }

    @PostMapping("/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(Authentication authentication, @PathVariable String token) {
        invites.decline(token, AuthenticatedUser.require(authentication));
    }

    /**
     * The peer address, and deliberately not X-Forwarded-For: the header is attacker
     * controlled unless a proxy is known to overwrite it, so keying a rate limit on it
     * hands out a fresh bucket per request. Matches AuthController. Revisit together with
     * it when a trusted proxy sits in front (then via Spring's ForwardedHeaderFilter,
     * not by reading the header here).
     */
    private static String clientIp(HttpServletRequest http) {
        String addr = http.getRemoteAddr();
        return addr == null ? "unknown" : addr;
    }
}
