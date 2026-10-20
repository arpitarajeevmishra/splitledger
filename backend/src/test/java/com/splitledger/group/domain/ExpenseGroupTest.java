package com.splitledger.group.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ExpenseGroupTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "inr", " INR "})
    void defaultsToOrNormalisesInr(String currency) {
        assertThat(ExpenseGroup.create("Flat 402", currency, UUID.randomUUID(), NOW).currency())
                .isEqualTo("INR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR", "GBP", "JPY"})
    void rejectsEveryOtherCurrencyWithOurOwnCode(String currency) {
        // Caught in the domain rather than by the database CHECK, so the caller sees a 422
        // naming the problem instead of a 500 naming a constraint.
        assertThatThrownBy(() -> ExpenseGroup.create("Trip", currency, UUID.randomUUID(), NOW))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CURRENCY_UNSUPPORTED);
    }

    @Test
    void trimsTheNameAndRecordsItsCreator() {
        UUID creator = UUID.randomUUID();

        ExpenseGroup group = ExpenseGroup.create("  Nordic Trip  ", "INR", creator, NOW);

        assertThat(group.name()).isEqualTo("Nordic Trip");
        assertThat(group.createdBy()).isEqualTo(creator);
        assertThat(group.createdAt()).isEqualTo(NOW);
        assertThat(group.id()).isNotNull();
    }
}
