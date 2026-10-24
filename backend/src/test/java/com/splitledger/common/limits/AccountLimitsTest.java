package com.splitledger.common.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountLimitsTest {

    private final AccountLimits limits = new AccountLimits(3, 5, 10);

    @Test
    void allows_everything_below_the_cap() {
        limits.requireRoomForGroup(2);
        limits.requireRoomForMember(4);
        limits.requireRoomForExpense(9);
    }

    @Test
    void refuses_at_the_cap_not_past_it() {
        // The caller passes the count *before* its write, so equality is already full. An
        // off-by-one here would allow one more of everything than the configuration says.
        assertThatThrownBy(() -> limits.requireRoomForGroup(3)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> limits.requireRoomForMember(5)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> limits.requireRoomForExpense(10)).isInstanceOf(ApiException.class);
    }

    @Test
    void every_cap_answers_with_the_same_422_code() {
        assertThat(codeOf(() -> limits.requireRoomForGroup(3)))
                .isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
        assertThat(codeOf(() -> limits.requireRoomForMember(5)))
                .isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
        assertThat(codeOf(() -> limits.requireRoomForExpense(10)))
                .isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
        assertThat(ErrorCode.ACCOUNT_LIMIT_REACHED.status().value()).isEqualTo(422);
    }

    @Test
    void each_message_names_the_cap_and_the_count() {
        // "A limit was reached" tells someone nothing they can act on. SL-906 asks for a
        // clear 422, and clear means the number they hit and the number they are at.
        assertThatThrownBy(() -> limits.requireRoomForGroup(3))
                .hasMessageContaining("3 groups")
                .hasMessageContaining("yours has 3");
        assertThatThrownBy(() -> limits.requireRoomForMember(5))
                .hasMessageContaining("5 members")
                .hasMessageContaining("this one has 5");
        assertThatThrownBy(() -> limits.requireRoomForExpense(12))
                .hasMessageContaining("10 expenses")
                .hasMessageContaining("recorded 12")
                .hasMessageContaining("24 hours");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void refuses_to_start_with_an_unsatisfiable_cap(int bad) {
        // Zero locks the product instead of capping it, and a negative cap rejects the
        // first request while claiming a limit of -1. Both are configuration mistakes that
        // should stop the application, not surprise a user.
        assertThatThrownBy(() -> new AccountLimits(bad, 5, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("groups-per-account");
        assertThatThrownBy(() -> new AccountLimits(3, bad, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("members-per-group");
        assertThatThrownBy(() -> new AccountLimits(3, 5, bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expenses-per-day");
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected an ApiException");
        } catch (ApiException ex) {
            return ex.code();
        }
    }
}
