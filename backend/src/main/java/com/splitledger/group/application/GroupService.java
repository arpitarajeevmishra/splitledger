package com.splitledger.group.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.limits.AccountLimits;
import com.splitledger.group.api.dto.GroupDetailResponse;
import com.splitledger.group.api.dto.GroupMemberResponse;
import com.splitledger.group.api.dto.GroupSummaryResponse;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.group.infra.GroupRepository;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final UserRepository users;
    private final GroupAccessGuard guard;
    private final AccountLimits limits;
    private final Clock clock;

    @Autowired
    GroupService(GroupRepository groups, GroupMemberRepository members, UserRepository users,
            GroupAccessGuard guard, AccountLimits limits) {
        this(groups, members, users, guard, limits, Clock.systemUTC());
    }

    /** Visible for testing. */
    GroupService(GroupRepository groups, GroupMemberRepository members, UserRepository users,
            GroupAccessGuard guard, AccountLimits limits, Clock clock) {
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.guard = guard;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * The group and the creator's ADMIN membership are written in one transaction. A group
     * with no members would be unreachable by its own access rules -- invisible to
     * everyone including the person who just made it.
     */
    @Transactional
    public GroupDetailResponse create(UUID creatorId, String name, String currency) {
        limits.requireRoomForGroup(groups.countByCreatedBy(creatorId));
        Instant now = Instant.now(clock);
        ExpenseGroup group = groups.save(ExpenseGroup.create(name, currency, creatorId, now));
        members.save(GroupMember.join(group.id(), creatorId, Role.ADMIN, now));
        return detailOf(group, Role.ADMIN);
    }

    @Transactional(readOnly = true)
    public List<GroupSummaryResponse> listMine(UUID userId) {
        return groups.findAllForMember(userId).stream()
                .map(group -> summaryOf(group, guard.requireMember(group.id(), userId).role()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GroupSummaryResponse> listArchived(UUID userId) {
        return groups.findAllArchivedForMember(userId).stream()
                .map(group -> summaryOf(group, guard.requireMember(group.id(), userId).role()))
                .toList();
    }

    /**
     * Put a group away.
     *
     * <p>Admin only, and reversible. What it is not is a delete: the ledger is immutable
     * (ADR-003) and a group's rows are the evidence behind every balance its members have
     * ever seen, so removing them would either orphan postings or erase the record someone
     * settled money against. Archiving hides it and stops new entries; the history stays
     * exactly where it was.
     *
     * <p>Archiving a group that still owes money is allowed on purpose. People abandon
     * shared flats without squaring up, and refusing to tidy the list until a debt nobody
     * intends to pay is settled would make the button useless precisely when it is wanted.
     * The balances remain visible inside the archived group.
     */
    @Transactional
    public GroupDetailResponse archive(UUID groupId, UUID actorId) {
        guard.requireAdmin(groupId, actorId);
        ExpenseGroup group = groups.findById(groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such group"));
        group.archive(Instant.now(clock));
        groups.save(group);
        return detailOf(group, guard.requireMember(groupId, actorId).role());
    }

    @Transactional
    public GroupDetailResponse unarchive(UUID groupId, UUID actorId) {
        guard.requireAdmin(groupId, actorId);
        ExpenseGroup group = groups.findById(groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such group"));
        group.unarchive();
        groups.save(group);
        return detailOf(group, guard.requireMember(groupId, actorId).role());
    }

    @Transactional(readOnly = true)
    public GroupDetailResponse detail(UUID groupId, UUID userId) {
        // Guard first, always. Loading the group before checking access would make the
        // 404-vs-403 distinction pointless, because timing and error shape would differ.
        GroupMember me = guard.requireMember(groupId, userId);
        ExpenseGroup group = groups.findById(groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such group"));
        return detailOf(group, me.role());
    }

    private GroupSummaryResponse summaryOf(ExpenseGroup group, Role yourRole) {
        return new GroupSummaryResponse(group.id(), group.name(), group.currency(), yourRole,
                (int) members.countByIdGroupIdAndLeftAtIsNull(group.id()), group.createdAt(),
                group.isArchived());
    }

    private GroupDetailResponse detailOf(ExpenseGroup group, Role yourRole) {
        List<GroupMember> active = members.findAllByIdGroupIdAndLeftAtIsNull(group.id());
        Set<UUID> ids = active.stream().map(GroupMember::userId).collect(Collectors.toSet());
        Map<UUID, User> byId = users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::id, Function.identity()));

        List<GroupMemberResponse> memberViews = active.stream()
                .map(member -> new GroupMemberResponse(
                        member.userId(),
                        // Name only. A member list is not an address book: email and
                        // payment details are never broadcast to the group.
                        byId.containsKey(member.userId())
                                ? byId.get(member.userId()).name()
                                : "Former member",
                        member.role(),
                        member.joinedAt()))
                .sorted(Comparator.comparing(GroupMemberResponse::joinedAt))
                .toList();

        return new GroupDetailResponse(group.id(), group.name(), group.currency(), yourRole,
                group.createdAt(), memberViews, group.archivedAt());
    }
}
