package com.splitledger.expense.application;

import java.util.List;
import java.util.UUID;

/**
 * What the expense module announces, and the shape it announces it in.
 *
 * <p>The payload is a published contract, not a view of the entity. Serialising
 * {@code Expense} itself would make every column an accidental promise and every rename a
 * consumer's problem -- and would put a Hibernate proxy in front of a JSON writer. These
 * records carry what a consumer plausibly needs and nothing else.
 *
 * <p>Enough to act on without a read-back: a notification saying "Priya added Dinner,
 * ₹900" should not need to query the expense that may since have been voided.
 */
public final class ExpenseEvents {

    public static final String AGGREGATE = "expense";
    public static final String CREATED = "expense.created";
    public static final String VOIDED = "expense.voided";

    private ExpenseEvents() {
    }

    /** @param participants everyone who owes a share, which is who a consumer must reach */
    public record Created(UUID expenseId, UUID groupId, UUID actorId, UUID paidBy,
            String description, long amountPaise, List<UUID> participants) {
    }

    /**
     * @param participants carried on the void too, because the people to tell are the
     *     people who owed a share -- and by the time a consumer handles this, the splits
     *     belong to an expense that no longer stands
     */
    public record Voided(UUID expenseId, UUID groupId, UUID actorId, String description,
            long amountPaise, List<UUID> participants) {
    }
}
