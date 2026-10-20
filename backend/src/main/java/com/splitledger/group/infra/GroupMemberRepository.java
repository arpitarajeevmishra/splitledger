package com.splitledger.group.infra;

import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.GroupMemberId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    Optional<GroupMember> findByIdGroupIdAndIdUserId(UUID groupId, UUID userId);

    List<GroupMember> findAllByIdGroupIdAndLeftAtIsNull(UUID groupId);

    long countByIdGroupIdAndLeftAtIsNull(UUID groupId);
}
