package com.splitledger.group.domain;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Maps expense_groups from V1__init.sql. */
@Entity
@Table(name = "expense_groups")
public class ExpenseGroup {

    /** v1 is INR-only, and the column carries a CHECK that says so. */
    public static final String SUPPORTED_CURRENCY = "INR";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExpenseGroup() {
        // for JPA
    }

    private ExpenseGroup(UUID id, String name, String currency, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.currency = currency;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public static ExpenseGroup create(String name, String currency, UUID createdBy, Instant now) {
        String resolved = currency == null || currency.isBlank()
                ? SUPPORTED_CURRENCY
                : currency.trim().toUpperCase();
        if (!SUPPORTED_CURRENCY.equals(resolved)) {
            // Rejected here with our own code rather than by the database CHECK, so the
            // caller gets a 422 naming the problem instead of a 500 naming a constraint.
            throw new ApiException(ErrorCode.CURRENCY_UNSUPPORTED,
                    "Only " + SUPPORTED_CURRENCY + " is supported in this version");
        }
        return new ExpenseGroup(UUID.randomUUID(), name.trim(), resolved, createdBy, now);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String currency() {
        return currency;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void rename(String newName) {
        this.name = newName.trim();
    }
}
