package com.splitledger.balance.api.dto;

import com.splitledger.balance.application.NetPosition;

/**
 * @param netPaise positive when your groups owe you, negative when you owe them. Same
 *     convention as a per-group net, so two screens cannot disagree about the sign.
 * @param unsettledGroups how many groups the figure comes from
 * @param settled true when there is nothing owed either way, sent rather than inferred
 *     from {@code netPaise == 0} so a client never has to decide what zero means
 */
public record NetPositionResponse(long netPaise, int unsettledGroups, boolean settled) {

    public static NetPositionResponse of(NetPosition position) {
        return new NetPositionResponse(position.netPaise(), position.unsettledGroups(),
                position.isSettled());
    }
}
