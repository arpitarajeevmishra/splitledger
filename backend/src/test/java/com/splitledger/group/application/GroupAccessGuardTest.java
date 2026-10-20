package com.splitledger.group.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupMemberRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupAccessGuardTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private final UUID groupId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Mock
    private GroupMemberRepository members;

    private GroupAccessGuard guard() {
        return new GroupAccessGuard(members);
    }

    private static ErrorCode codeOf(Throwable ex) {
        return ((ApiException) ex).code();
    }

    @Test
    void anActiveMemberIsAllowedThrough() {
        GroupMember membership = GroupMember.join(groupId, userId, Role.MEMBER, NOW);
        when(members.findByIdGroupIdAndIdUserId(groupId, userId)).thenReturn(Optional.of(membership));

        assertThat(guard().requireMember(groupId, userId)).isSameAs(membership);
    }

    @Test
    void aNonMemberGets404AndNot403() {
        when(members.findByIdGroupIdAndIdUserId(groupId, userId)).thenReturn(Optional.empty());

        // The whole point of SL-202: a 403 would confirm the group exists, letting anyone
        // holding an id learn which ids are real without belonging to a single group.
        assertThatThrownBy(() -> guard().requireMember(groupId, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(codeOf(ex)).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(codeOf(ex)).isNotEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(codeOf(ex)).isNotEqualTo(ErrorCode.NOT_GROUP_MEMBER);
                });
    }

    @Test
    void aDepartedMemberIsTreatedAsANonMember() {
        GroupMember membership = GroupMember.join(groupId, userId, Role.MEMBER, NOW);
        membership.leave(NOW);
        when(members.findByIdGroupIdAndIdUserId(groupId, userId)).thenReturn(Optional.of(membership));

        // The row still exists because the ledger refers to it, but access is gone.
        assertThatThrownBy(() -> guard().requireMember(groupId, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void anAdminPassesTheAdminCheck() {
        when(members.findByIdGroupIdAndIdUserId(groupId, userId))
                .thenReturn(Optional.of(GroupMember.join(groupId, userId, Role.ADMIN, NOW)));

        assertThat(guard().requireAdmin(groupId, userId).isAdmin()).isTrue();
    }

    @Test
    void aPlainMemberFailingAnAdminCheckGets403AndNot404() {
        when(members.findByIdGroupIdAndIdUserId(groupId, userId))
                .thenReturn(Optional.of(GroupMember.join(groupId, userId, Role.MEMBER, NOW)));

        // 403 here is right, and the asymmetry with the test above is deliberate: this
        // caller already knows the group exists, so hiding the reason would only make the
        // UI unexplainable without concealing anything.
        assertThatThrownBy(() -> guard().requireAdmin(groupId, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
