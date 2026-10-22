package com.splitledger.group.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.infra.GroupRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a group is locked for a money-affecting write (ADR-004).
 *
 * <p>ADR-004's follow-up is the reason this class exists rather than a line of code in
 * each service: "taking the lock must live in one place in the application layer.
 * Scattered across services, it will eventually be forgotten in exactly the path that
 * matters." Expenses, settlements and corrections all come through here.
 *
 * <p>Writes within one group serialise; unrelated groups proceed in parallel, because the
 * lock is on that group's row and nothing wider.
 */
@Component
public class GroupLock {

    private final GroupRepository groups;

    GroupLock(GroupRepository groups) {
        this.groups = groups;
    }

    /**
     * Take the lock. Held until the surrounding transaction ends.
     *
     * <p>{@code MANDATORY} propagation on purpose: a row lock taken outside a transaction
     * is released the instant the query returns, so it would look like protection and be
     * none. Rather than trusting every future caller to remember {@code @Transactional},
     * calling this without one throws.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseGroup acquire(UUID groupId) {
        return groups.lockById(groupId)
                // Same reasoning as GroupAccessGuard: a caller who is not in the group
                // should not learn whether it exists.
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such group"));
    }
}
