package com.splitledger.common.limits;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-account abuse caps (SL-906, risk R13).
 *
 * <p>These are not product rules and they are not security. Authorisation already decides
 * who may do what; these decide how much of it one account can do before the bill, the
 * disk and the 2 GB of RAM this thing runs on become someone's problem. A demo with no
 * caps is a demo that can be turned into a bill.
 *
 * <p>Configurable rather than constant, for the same reason the rate limits are: a cap
 * that can only be changed by a release is a cap nobody adjusts when it turns out to be
 * wrong. Every one of them is tunable by environment variable.
 *
 * <p>The checks live here, next to the numbers, so the message a caller sees is written
 * once. Each one names the cap and the current count: "you have reached a limit" is not an
 * error message, it is a shrug.
 *
 * @param groupsPerAccount groups a single account may create
 * @param membersPerGroup active members one group may hold
 * @param expensesPerDay expenses an account may record within {@link #EXPENSE_WINDOW}
 */
@ConfigurationProperties(prefix = "app.limits")
public record AccountLimits(int groupsPerAccount, int membersPerGroup, int expensesPerDay) {

    /**
     * Rolling, not a calendar day.
     *
     * <p>A calendar window hands out a fresh quota at midnight, so an account at its cap
     * can record twice the daily allowance in the few minutes either side of it. A rolling
     * window has no such edge, and "in the last 24 hours" is also what the error message
     * can honestly say.
     */
    public static final Duration EXPENSE_WINDOW = Duration.ofHours(24);

    public AccountLimits {
        requirePositive("groups-per-account", groupsPerAccount);
        requirePositive("members-per-group", membersPerGroup);
        requirePositive("expenses-per-day", expensesPerDay);
    }

    /** @throws ApiException 422 when this account already has its full allowance of groups */
    public void requireRoomForGroup(long existing) {
        if (existing >= groupsPerAccount) {
            throw new ApiException(ErrorCode.ACCOUNT_LIMIT_REACHED,
                    "This demo allows " + groupsPerAccount + " groups per account, and yours has "
                            + existing + ".");
        }
    }

    /** @throws ApiException 422 when the group is full */
    public void requireRoomForMember(long active) {
        if (active >= membersPerGroup) {
            throw new ApiException(ErrorCode.ACCOUNT_LIMIT_REACHED,
                    "This demo allows " + membersPerGroup + " members in a group, and this one has "
                            + active + ".");
        }
    }

    /** @throws ApiException 422 when this account has recorded its allowance for the window */
    public void requireRoomForExpense(long recent) {
        if (recent >= expensesPerDay) {
            throw new ApiException(ErrorCode.ACCOUNT_LIMIT_REACHED,
                    "This demo allows " + expensesPerDay
                            + " expenses per account per day, and yours has recorded " + recent
                            + " in the last " + EXPENSE_WINDOW.toHours() + " hours.");
        }
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            // Zero would lock the product rather than cap it, and a negative cap would
            // reject the first attempt with a message claiming a limit of -1. Failing at
            // startup is the only honest response to a cap that cannot be satisfied.
            throw new IllegalStateException(
                    "app.limits." + name + " must be positive, was " + value);
        }
    }
}
