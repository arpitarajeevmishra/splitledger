package com.splitledger.expense.infra;

import com.splitledger.expense.domain.ExpenseSplit;
import com.splitledger.expense.domain.ExpenseSplitId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseSplitRepository extends JpaRepository<ExpenseSplit, ExpenseSplitId> {

    List<ExpenseSplit> findAllByIdExpenseId(UUID expenseId);
}
