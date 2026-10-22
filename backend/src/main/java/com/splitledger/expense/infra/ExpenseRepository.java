package com.splitledger.expense.infra;

import com.splitledger.expense.domain.Expense;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    List<Expense> findAllByGroupIdOrderByExpenseDateDescCreatedAtDesc(UUID groupId);
}
