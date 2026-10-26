package com.splitledger.group.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.security.SecureTokens;
import com.splitledger.group.domain.GroupInvite;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.InviteStatus;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupInviteRepository;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.group.infra.GroupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations: the only way anyone joins a group.
 *
 * <p>FR-10 says nobody is added to a group without consenting, so there is no
 * "add member" anywhere in this module -- an admin can only offer, and the invited person
 * decides. That is also why accepting is authenticated and matched against the invited
 * address: holding the link proves you received it, but joining a group that keeps a
 * record of who owes whom should be an act of the person who will owe.
 *
 * <p>The link carries a 256-bit random token and the database stores only its hash
 * (ADR-011's reasoning, applied to invites): a leaked backup yields no usable invitations
 * and the raw token exists only in the admin's creation response. The token is never
 * logged, because a log line is a credential nobody rotates.
 */
@Service
public class InviteService {

    private final GroupInviteRepository invites;
    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupAccessGuard guard;
    private final GroupMembershipService memberships;
    private final UserDirectory users;
    private final Duration ttl;
    private final String baseUrl;
    private final Clock clock;

    @Autowired
    InviteService(GroupInviteRepository invites, GroupRepository groups,
            GroupMemberRepository members, GroupAccessGuard guard,
            GroupMembershipService memberships, UserDirectory users,
            @Value("${app.invites.ttl}") Duration ttl,
            @Value("${app.invites.base-url}") String baseUrl) {
        this(invites, groups, members, guard, memberships, users, ttl, baseUrl, Clock.systemUTC());
    }

    /** Visible for testing. */
    InviteService(GroupInviteRepository invites, GroupRepository groups,
            GroupMemberRepository members, GroupAccessGuard guard,
            GroupMembershipService memberships, UserDirectory users, Duration ttl,
            String baseUrl, Clock clock) {
        this.invites = invites;
        this.groups = groups;
        this.members = members;
        this.guard = guard;
        this.memberships = memberships;
        this.users = users;
        this.ttl = ttl;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    /** An invite plus the one and only time its raw token is readable. */
    public record Issued(GroupInvite invite, String url) {
    }

    /** What someone holding a link may see before signing in. */
    public record Preview(String groupName, String email, Instant expiresAt) {
    }

    @Transactional
    public Issued invite(UUID groupId, UUID actorId, String rawEmail) {
        guard.requireAdmin(groupId, actorId);
        guard.requireActive(groupId);
        String email = users.normaliseEmail(rawEmail);

        boolean alreadyIn = users.userIdFor(email)
                .flatMap(userId -> members.findByIdGroupIdAndIdUserId(groupId, userId))
                .filter(GroupMember::isActive)
                .isPresent();
        if (alreadyIn) {
            throw new ApiException(ErrorCode.ALREADY_GROUP_MEMBER,
                    "They are already in this group");
        }

        Instant now = Instant.now(clock);

        // Re-inviting replaces rather than stacks: a partial unique index allows one
        // PENDING invite per (group, email), and two live links to the same group would
        // mean revoking one left the other working.
        invites.findByGroupIdAndEmailAndStatus(groupId, email, InviteStatus.PENDING)
                .ifPresent(open -> {
                    open.revoke(now);
                    invites.save(open);
                    // The replacement collides with uq_invite_open unless this revocation
                    // has reached the database first. Both happen in one transaction, so
                    // writing them in the right order is not enough -- Hibernate decides
                    // when they are sent.
                    invites.flush();
                });

        String rawToken = SecureTokens.random();
        GroupInvite invite = GroupInvite.issue(groupId, email, actorId,
                SecureTokens.hash(rawToken), now, now.plus(ttl));
        try {
            invites.save(invite);
            // Flushed here so the index has its say inside this method. Left to commit,
            // the violation would surface after the controller had returned and the
            // handler would have nothing to translate -- a 500 for a double-clicked
            // button.
            invites.flush();
        } catch (DataIntegrityViolationException ex) {
            // Two admins inviting the same person at once: both passed the check above,
            // and uq_invite_open lets exactly one of them win. The loser is told to look
            // again rather than shown a 500; the transaction rolls back, which is right,
            // because no invitation of theirs exists.
            throw new ApiException(ErrorCode.INVITE_ALREADY_PENDING,
                    "An invitation to that address was just created");
        }
        return new Issued(invite, baseUrl + "/invite/" + rawToken);
    }

    @Transactional(readOnly = true)
    public List<GroupInvite> list(UUID groupId, UUID actorId) {
        guard.requireAdmin(groupId, actorId);
        return invites.findAllByGroupIdOrderByCreatedAtDesc(groupId);
    }

    @Transactional
    public void revoke(UUID groupId, UUID inviteId, UUID actorId) {
        guard.requireAdmin(groupId, actorId);
        GroupInvite invite = invites.findById(inviteId)
                // Checked against the group in the path, not just the id: otherwise an
                // admin of one group could revoke invitations belonging to another.
                .filter(candidate -> candidate.groupId().equals(groupId))
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID, "No such invite"));

        if (invite.status() != InviteStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "That invite has already been answered");
        }
        invite.revoke(Instant.now(clock));
        invites.save(invite);
    }

    /**
     * What the landing page shows before the recipient has signed in, so it can say which
     * group they were invited to and which address to sign in with. Readable by anyone
     * holding the link, which is the point: the token is the credential.
     */
    @Transactional(readOnly = true)
    public Preview preview(String rawToken) {
        GroupInvite invite = usable(rawToken);
        String groupName = groups.findById(invite.groupId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID,
                        "That group no longer exists"))
                .name();
        return new Preview(groupName, invite.email(), invite.expiresAt());
    }

    @Transactional
    public UUID accept(String rawToken, UUID userId) {
        GroupInvite invite = usable(rawToken);
        requireAddressedTo(invite, userId);
        // The check that makes requireAddressedTo mean anything (SL-901). Matching the
        // caller's email against the invitation proves nothing on its own: anybody can
        // register an address they do not own. Without this, an invitation addressed to
        // one person is accepted by whoever registered that address first, and they read
        // the group's members, expenses and balances.
        //
        // On accept rather than at registration, deliberately. An unverified account is
        // harmless on its own -- its own groups, its own expenses, nobody else's data --
        // and blocking registration until a mailbox has been read would mean nobody can
        // try the product at all while SL-902 is outstanding.
        requireVerified(userId);

        Instant now = Instant.now(clock);
        invite.accept(now);
        invites.save(invite);
        // joinOrRejoin and not an insert: someone who left this group before still has a
        // row, and the primary key forbids a second one.
        memberships.joinOrRejoin(invite.groupId(), userId, Role.MEMBER);
        return invite.groupId();
    }

    @Transactional
    public void decline(String rawToken, UUID userId) {
        GroupInvite invite = usable(rawToken);
        // Declining is checked as strictly as accepting: otherwise anyone who came across
        // the link could burn an invitation that was not theirs.
        requireAddressedTo(invite, userId);
        invite.decline(Instant.now(clock));
        invites.save(invite);
    }

    /**
     * Declining is not gated.
     *
     * <p>Refusing an invitation reveals nothing and joins nothing, and somebody who cannot
     * verify should still be able to make the link stop being offered to them.
     */
    private void requireVerified(UUID userId) {
        if (!users.isEmailVerified(userId)) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Verify your email address before joining a group");
        }
    }

    private void requireAddressedTo(GroupInvite invite, UUID userId) {
        String email = users.emailOf(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Unknown user"));
        if (!invite.email().equals(users.normaliseEmail(email))) {
            throw new ApiException(ErrorCode.INVITE_EMAIL_MISMATCH,
                    "This invite was sent to a different address");
        }
    }

    /**
     * One lookup for every redemption path. Unknown, revoked, answered and expired all
     * return the same 404: distinguishing them would tell a stranger holding a guessed
     * token whether it ever existed.
     */
    private GroupInvite usable(String rawToken) {
        GroupInvite invite = invites.findByTokenHash(SecureTokens.hash(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID,
                        "Unknown, revoked or expired invite"));
        if (!invite.isUsable(Instant.now(clock))) {
            throw new ApiException(ErrorCode.INVITE_INVALID,
                    "Unknown, revoked or expired invite");
        }
        return invite;
    }

    /** Exposed so the API layer can label invites without re-deriving the clock. */
    public Instant now() {
        return Instant.now(clock);
    }
}
