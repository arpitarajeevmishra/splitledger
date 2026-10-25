package com.splitledger.balance.application;

/**
 * Where one person stands across every group they are in.
 *
 * @param netPaise positive when the groups owe them, negative when they owe. The same
 *     sign convention as a per-group net, so the dashboard and a group page cannot
 *     disagree about which way round a number is.
 * @param unsettledGroups how many groups contribute a non-zero figure, so the screen can
 *     say where the total comes from. Zero means everything is square -- which is a
 *     different statement from "you have no groups", and the UI is able to tell them
 *     apart because it has the group list too.
 */
public record NetPosition(long netPaise, int unsettledGroups) {

    public boolean isSettled() {
        return netPaise == 0L;
    }
}
