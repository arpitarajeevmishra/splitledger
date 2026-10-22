package com.splitledger.expense.infra;

import com.splitledger.expense.domain.Expense;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    List<Expense> findAllByGroupIdOrderByExpenseDateDescCreatedAtDesc(UUID groupId);

    /**
     * Just the group, so a caller can take the group lock before loading the expense
     * itself.
     *
     * <p>A scalar projection on purpose: {@code findById} would put the entity in the
     * persistence context at its pre-lock version, and every later read in the same
     * transaction would quietly return that stale copy -- including the optimistic-lock
     * check, which would then compare a version against itself.
     */
    @Query("select e.groupId from Expense e where e.id = :expenseId")
    Optional<UUID> findGroupIdById(@Param("expenseId") UUID expenseId);
}
