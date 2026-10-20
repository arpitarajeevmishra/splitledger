package com.splitledger.group.application;

import java.util.UUID;

/**
 * Whether a member still owes or is owed anything in a group.
 *
 * <p>Balances do not exist until S4, so {@link ZeroBalanceProbe} answers zero for now.
 * The **rule** is written and tested today rather than retrofitted later: removing a
 * member who still owes money would silently drop a debt from the ledger, and that is not
 * a check to bolt on once there is money to get wrong. When the ledger lands, S4
 * implements this one method and the rule starts biting with no other change.
 */
public interface GroupBalanceProbe {

    /** @return net balance in paise; zero means fully settled */
    long netBalancePaise(UUID groupId, UUID userId);
}
