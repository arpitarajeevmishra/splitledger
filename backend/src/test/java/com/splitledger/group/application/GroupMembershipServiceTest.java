package com.splitledger.group.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupMembershipServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private GroupMemberRepository members;
    @Mock
    private GroupAccessGuard guard;
    @Mock
    private GroupBalanceProbe balances;

    private GroupMembershipService service;
    private UUID groupId;
    private UUID adminId;
    private UUID memberId;

    private static ErrorCode codeOf(Throwable ex) {
        return ((ApiException) ex).code();
    }

    @BeforeEach
    void setUp() {
        service = new GroupMembershipService(members, guard, balances,
                Clock.fixed(NOW, ZoneOffset.UTC));
        groupId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        when(members.save(any(GroupMember.class))).thenAnswer(i -> i.getArgument(0));
        when(balances.netBalancePaise(any(), any())).thenReturn(0L);
    }

    private GroupMember admin() {
        return GroupMember.join(groupId, adminId, Role.ADMIN, NOW);
    }

    private GroupMember member() {
        return GroupMember.join(groupId, memberId, Role.MEMBER, NOW);
    }

    @Test
    void joiningForTheFirstTimeInsertsAMembership() {
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId)).thenReturn(Optional.empty());

        GroupMember joined = service.joinOrRejoin(groupId, memberId, Role.MEMBER);

        assertThat(joined.isActive()).isTrue();
        assertThat(joined.role()).isEqualTo(Role.MEMBER);
    }

    @Test
    void rejoiningReusesTheExistingRowRatherThanInsertingASecond() {
        GroupMember departed = member();
        departed.leave(NOW);
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId))
                .thenReturn(Optional.of(departed));

        GroupMember rejoined = service.joinOrRejoin(groupId, memberId, Role.MEMBER);

        // The primary key is (group_id, user_id), so a second insert would collide -- and
        // the ledger still refers to this row (finding C22).
        assertThat(rejoined).isSameAs(departed);
        assertThat(rejoined.isActive()).isTrue();
        assertThat(rejoined.leftAt()).isNull();
    }

    @Test
    void joiningTwiceIsANoOp() {
        GroupMember active = member();
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId)).thenReturn(Optional.of(active));

        service.joinOrRejoin(groupId, memberId, Role.MEMBER);

        verify(members, never()).save(any(GroupMember.class));
    }

    @Test
    void anAdminRemovesAMember() {
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId)).thenReturn(Optional.of(member()));
        when(members.findAllByIdGroupIdAndLeftAtIsNull(groupId))
                .thenReturn(List.of(admin(), member()));

        service.remove(groupId, adminId, memberId);

        verify(guard).requireAdmin(groupId, adminId);
    }

    @Test
    void removingYourselfChecksMembershipRatherThanAdminRights() {
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId)).thenReturn(Optional.of(member()));
        when(members.findAllByIdGroupIdAndLeftAtIsNull(groupId))
                .thenReturn(List.of(admin(), member()));

        service.remove(groupId, memberId, memberId);

        // Leaving is not an admin action; a plain member may always walk away.
        verify(guard).requireMember(groupId, memberId);
        verify(guard, never()).requireAdmin(any(), any());
    }

    @Test
    void refusesToRemoveSomeoneWhoStillOwesTheGroup() {
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId)).thenReturn(Optional.of(member()));
        when(balances.netBalancePaise(groupId, memberId)).thenReturn(-30000L);

        assertThatThrownBy(() -> service.remove(groupId, adminId, memberId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex))
                        .isEqualTo(ErrorCode.MEMBER_HAS_OUTSTANDING_BALANCE))
                .hasMessageContaining("owe");
    }

    @Test
    void refusesToRemoveSomeoneTheGroupStillOwes() {
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId)).thenReturn(Optional.of(member()));
        when(balances.netBalancePaise(groupId, memberId)).thenReturn(45000L);

        // Both directions matter: dropping a creditor loses money owed *to* them just as
        // surely as dropping a debtor loses money owed *by* them.
        assertThatThrownBy(() -> service.remove(groupId, adminId, memberId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex))
                        .isEqualTo(ErrorCode.MEMBER_HAS_OUTSTANDING_BALANCE));
    }

    @Test
    void refusesToRemoveTheLastAdmin() {
        when(members.findByIdGroupIdAndIdUserId(groupId, adminId)).thenReturn(Optional.of(admin()));
        when(members.findAllByIdGroupIdAndLeftAtIsNull(groupId))
                .thenReturn(List.of(admin(), member()));

        // A group with members but no admin can never be administered again.
        assertThatThrownBy(() -> service.remove(groupId, adminId, adminId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ErrorCode.LAST_ADMIN));
    }

    @Test
    void allowsAnAdminToLeaveWhenAnotherAdminRemains() {
        UUID secondAdmin = UUID.randomUUID();
        when(members.findByIdGroupIdAndIdUserId(groupId, adminId)).thenReturn(Optional.of(admin()));
        when(members.findAllByIdGroupIdAndLeftAtIsNull(groupId))
                .thenReturn(List.of(admin(), GroupMember.join(groupId, secondAdmin, Role.ADMIN, NOW)));

        service.remove(groupId, adminId, adminId);

        verify(members).save(any(GroupMember.class));
    }

    @Test
    void removingSomeoneWhoIsNotAMemberIs404() {
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(groupId, adminId, memberId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void aDepartedMemberCannotBeRemovedAgain() {
        GroupMember departed = member();
        departed.leave(NOW);
        when(members.findByIdGroupIdAndIdUserId(groupId, memberId))
                .thenReturn(Optional.of(departed));

        assertThatThrownBy(() -> service.remove(groupId, adminId, memberId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
