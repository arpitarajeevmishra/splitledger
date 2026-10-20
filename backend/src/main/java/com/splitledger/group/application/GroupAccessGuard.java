package com.splitledger.group.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.infra.GroupMemberRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place group access is decided. Every group-scoped service entry point calls
 * one of these before touching anything (HLD section 11, LLD section 12).
 *
 * <p><b>Non-members get 404, not 403.</b> A 403 confirms the group exists, which lets
 * anyone with a group id learn which ids are real -- so an attacker can enumerate groups
 * without being in any of them. 404 says only "nothing here for you".
 *
 * <p>The distinction is deliberate, not blanket: a member who lacks a role for an action
 * does get 403, because they already know the group exists, and hiding the reason would
 * only make the UI unexplainable.
 */
@Component
public class GroupAccessGuard {

    private final GroupMemberRepository members;

    GroupAccessGuard(GroupMemberRepository members) {
        this.members = members;
    }

    @Transactional(readOnly = true)
    public GroupMember requireMember(UUID groupId, UUID userId) {
        return members.findByIdGroupIdAndIdUserId(groupId, userId)
                .filter(GroupMember::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such group"));
    }

    @Transactional(readOnly = true)
    public GroupMember requireAdmin(UUID groupId, UUID userId) {
        GroupMember member = requireMember(groupId, userId);
        if (!member.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Only a group admin can do that");
        }
        return member;
    }
}
