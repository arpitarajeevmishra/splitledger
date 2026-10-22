package com.splitledger.group.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.limits.AccountLimits;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Joining, leaving and removal. Adding someone is never done here -- see the invite flow. */
@Service
public class GroupMembershipService {

    private final GroupMemberRepository members;
    private final GroupAccessGuard guard;
    private final GroupBalanceProbe balances;
    private final GroupLock groupLock;
    private final AccountLimits limits;
    private final Clock clock;

    @Autowired
    GroupMembershipService(GroupMemberRepository members, GroupAccessGuard guard,
            GroupBalanceProbe balances, GroupLock groupLock, AccountLimits limits) {
        this(members, guard, balances, groupLock, limits, Clock.systemUTC());
    }

    /** Visible for testing. */
    GroupMembershipService(GroupMemberRepository members, GroupAccessGuard guard,
            GroupBalanceProbe balances, GroupLock groupLock, AccountLimits limits, Clock clock) {
        this.members = members;
        this.guard = guard;
        this.balances = balances;
        this.groupLock = groupLock;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Join a group, or re-join after leaving.
     *
     * <p>An upsert, not an insert: the primary key is (group_id, user_id), so a departed
     * member already has a row and a second insert would collide. Their history stays
     * attached to that row, which is the point -- the ledger still refers to them
     * (finding C22).
     *
     * <p>Called by the invite flow once an invitation is accepted. Nothing else may add a
     * member: FR-10 says nobody joins a group without consenting.
     *
     * <p>Takes the group lock before counting members, which is the only way the cap can
     * be exact: two invitations accepted at the same instant would otherwise both read a
     * group with one place left and both take it. Joins to one group serialise; different
     * groups never wait on each other (ADR-004).
     */
    @Transactional
    public GroupMember joinOrRejoin(UUID groupId, UUID userId, Role role) {
        Instant now = Instant.now(clock);
        return members.findByIdGroupIdAndIdUserId(groupId, userId)
                .map(existing -> {
                    if (existing.isActive()) {
                        return existing;   // already in; joining twice is a no-op
                    }
                    // A re-join raises the active count exactly as a first join does, so
                    // it is capped too -- otherwise leaving and re-joining is a way past it.
                    requireRoom(groupId);
                    existing.rejoin(role, now);
                    return members.save(existing);
                })
                .orElseGet(() -> {
                    requireRoom(groupId);
                    return members.save(GroupMember.join(groupId, userId, role, now));
                });
    }

    private void requireRoom(UUID groupId) {
        groupLock.acquire(groupId);
        limits.requireRoomForMember(members.countByIdGroupIdAndLeftAtIsNull(groupId));
    }

    /**
     * Remove a member, or leave if removing yourself.
     *
     * <p>An admin may remove anyone; anyone may remove themselves. Both run the same
     * checks, because "leaving while you still owe money" is the same problem as being
     * removed while you do.
     */
    @Transactional
    public void remove(UUID groupId, UUID actorId, UUID targetUserId) {
        if (actorId.equals(targetUserId)) {
            guard.requireMember(groupId, actorId);
        } else {
            guard.requireAdmin(groupId, actorId);
        }

        GroupMember target = members.findByIdGroupIdAndIdUserId(groupId, targetUserId)
                .filter(GroupMember::isActive)
                // Same reasoning as the access guard: do not confirm who is in a group to
                // someone asking about a user who is not.
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such member"));

        long balance = balances.netBalancePaise(groupId, targetUserId);
        if (balance != 0L) {
            // Removing someone mid-debt would drop their share from every split that
            // referenced them. The debt is real until settled or reversed.
            throw new ApiException(ErrorCode.MEMBER_HAS_OUTSTANDING_BALANCE,
                    balance < 0
                            ? "They still owe the group; settle up first"
                            : "The group still owes them; settle up first");
        }

        if (target.isAdmin() && isLastAdmin(groupId)) {
            // A group with members but no admin can never be administered again: nobody
            // could invite, remove, or hand the role over.
            throw new ApiException(ErrorCode.LAST_ADMIN, "Promote another member to admin first");
        }

        target.leave(Instant.now(clock));
        members.save(target);
    }

    private boolean isLastAdmin(UUID groupId) {
        return members.findAllByIdGroupIdAndLeftAtIsNull(groupId).stream()
                .filter(GroupMember::isAdmin)
                .count() <= 1L;
    }
}
