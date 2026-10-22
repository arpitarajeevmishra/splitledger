package com.splitledger.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A purchase someone made on behalf of a group.
 *
 * <p>An expense is a statement of fact, so it is never edited in place: a correction voids
 * this row and writes a new one pointing back at it with {@code replacesExpenseId}, and
 * the ledger gets a reversal (LLD 5.2). What a group believed last Tuesday stays readable.
 *
 * <p>{@code version} is the optimistic lock for those corrections (ADR-004). The per-group
 * row lock serialises writes; this stops two people who both read the same expense from
 * both succeeding in changing it.
 */
@Entity
@Table(name = "expenses")
public class Expense {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "paid_by", nullable = false, updatable = false)
    private UUID paidBy;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    // CHAR(3) in the schema; without this Hibernate maps String to varchar and
    // ddl-auto: validate rejects the mismatch (D-42).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false, updatable = false, length = 10)
    private SplitType splitType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ExpenseStatus status;

    @Column(name = "replaces_expense_id", updatable = false)
    private UUID replacesExpenseId;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Expense() {
        // for JPA
    }

    private Expense(UUID id, UUID groupId, UUID paidBy, String description, long amountPaise,
            String currency, SplitType splitType, LocalDate expenseDate, UUID createdBy,
            Instant createdAt) {
        this.id = id;
        this.groupId = groupId;
        this.paidBy = paidBy;
        this.description = description;
        this.amountPaise = amountPaise;
        this.currency = currency;
        this.splitType = splitType;
        this.status = ExpenseStatus.ACTIVE;
        this.expenseDate = expenseDate;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public static Expense record(UUID groupId, UUID paidBy, String description, long amountPaise,
            String currency, SplitType splitType, LocalDate expenseDate, UUID createdBy,
            Instant now) {
        return new Expense(UUID.randomUUID(), groupId, paidBy, description.trim(), amountPaise,
                currency, splitType, expenseDate, createdBy, now);
    }

    public boolean isActive() {
        return status == ExpenseStatus.ACTIVE;
    }

    public void voidIt() {
        this.status = ExpenseStatus.VOIDED;
    }

    public UUID id() {
        return id;
    }

    public UUID groupId() {
        return groupId;
    }

    public UUID paidBy() {
        return paidBy;
    }

    public String description() {
        return description;
    }

    public long amountPaise() {
        return amountPaise;
    }

    public String currency() {
        return currency;
    }

    public SplitType splitType() {
        return splitType;
    }

    public ExpenseStatus status() {
        return status;
    }

    public UUID replacesExpenseId() {
        return replacesExpenseId;
    }

    public LocalDate expenseDate() {
        return expenseDate;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
