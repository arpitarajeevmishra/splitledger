package com.splitledger.group.api;

import com.splitledger.common.ratelimit.RateLimiter;
import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.group.api.dto.CreateInviteRequest;
import com.splitledger.group.api.dto.InviteResponse;
import com.splitledger.group.api.dto.IssuedInviteResponse;
import com.splitledger.group.application.InviteService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Managing a group's invitations. Admin only; the guard is in the service. */
@RestController
@RequestMapping("/api/v1/groups/{groupId}/invites")
public class GroupInviteController {

    private final InviteService invites;
    private final RateLimiter rateLimiter;

    GroupInviteController(InviteService invites, RateLimiter rateLimiter) {
        this.invites = invites;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedInviteResponse create(Authentication authentication, @PathVariable UUID groupId,
            @Valid @RequestBody CreateInviteRequest request) {
        UUID actorId = AuthenticatedUser.require(authentication);
        // Keyed by the admin, not by IP: the cost of this endpoint is invitations sent to
        // other people's addresses, and one account behind one address is the thing to cap.
        rateLimiter.check("invite-create", actorId.toString());

        InviteService.Issued issued = invites.invite(groupId, actorId, request.email());
        return new IssuedInviteResponse(issued.invite().id(), issued.invite().email(),
                issued.url(), issued.invite().expiresAt());
    }

    @GetMapping
    public List<InviteResponse> list(Authentication authentication, @PathVariable UUID groupId) {
        Instant now = invites.now();
        return invites.list(groupId, AuthenticatedUser.require(authentication)).stream()
                .map(invite -> InviteResponse.of(invite, now))
                .toList();
    }

    @DeleteMapping("/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication authentication, @PathVariable UUID groupId,
            @PathVariable UUID inviteId) {
        invites.revoke(groupId, inviteId, AuthenticatedUser.require(authentication));
    }
}
