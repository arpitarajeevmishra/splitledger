package com.splitledger.group.infra;

import com.splitledger.group.domain.ExpenseGroup;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<ExpenseGroup, UUID> {

    /**
     * Groups this account created, however many it has since left. Leaving one must not
     * hand back an allowance to create another, or the cap bounds nothing.
     */
    long countByCreatedBy(UUID createdBy);

    /**
     * Only groups the caller is still an active member of, and that are still in use.
     *
     * <p>Archived ones are excluded here rather than filtered by the caller: a list
     * endpoint that returns them and trusts every client to hide them is one forgotten
     * filter away from showing people the thing they put away.
     */
    @Query("select g from ExpenseGroup g where g.archivedAt is null and g.id in ("
            + "select m.id.groupId from GroupMember m "
            + "where m.id.userId = :userId and m.leftAt is null) "
            + "order by g.createdAt desc")
    List<ExpenseGroup> findAllForMember(@Param("userId") UUID userId);

    /** The ones that were put away, so they can be found again. */
    @Query("select g from ExpenseGroup g where g.archivedAt is not null and g.id in ("
            + "select m.id.groupId from GroupMember m "
            + "where m.id.userId = :userId and m.leftAt is null) "
            + "order by g.archivedAt desc")
    List<ExpenseGroup> findAllArchivedForMember(@Param("userId") UUID userId);

    /**
     * SELECT ... FOR UPDATE on the group row (ADR-004).
     *
     * <p>A separate method rather than a lock on {@code findById}, because almost every
     * read of a group wants no lock at all and silently locking them would serialise the
     * whole product.
     *
     * <p>Call it through {@code GroupLock}, never directly.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from ExpenseGroup g where g.id = :id")
    Optional<ExpenseGroup> lockById(@Param("id") UUID id);
}
