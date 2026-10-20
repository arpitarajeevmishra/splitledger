package com.splitledger.group.infra;

import com.splitledger.group.domain.GroupInvite;
import com.splitledger.group.domain.InviteStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupInviteRepository extends JpaRepository<GroupInvite, UUID> {

    Optional<GroupInvite> findByTokenHash(String tokenHash);

    List<GroupInvite> findAllByGroupIdOrderByCreatedAtDesc(UUID groupId);

    Optional<GroupInvite> findByGroupIdAndEmailAndStatus(UUID groupId, String email,
            InviteStatus status);
}
