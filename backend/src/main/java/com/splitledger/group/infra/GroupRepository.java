package com.splitledger.group.infra;

import com.splitledger.group.domain.ExpenseGroup;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<ExpenseGroup, UUID> {

    /** Only groups the caller is still an active member of. */
    @Query("select g from ExpenseGroup g where g.id in ("
            + "select m.id.groupId from GroupMember m "
            + "where m.id.userId = :userId and m.leftAt is null) "
            + "order by g.createdAt desc")
    List<ExpenseGroup> findAllForMember(@Param("userId") UUID userId);
}
