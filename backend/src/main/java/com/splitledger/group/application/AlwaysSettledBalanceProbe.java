package com.splitledger.group.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Placeholder until the ledger exists (S4): reports everyone as settled.
 *
 * <p>This is correct today rather than a lie -- with no expenses, nobody can owe anything.
 *
 * <p><b>Delete this class in S4</b>, when the ledger provides a real
 * {@link GroupBalanceProbe}. It is deliberately a plain {@code @Component} rather than a
 * {@code @ConditionalOnMissingBean} fallback: that annotation is only dependable inside
 * auto-configuration, and if its ordering went the wrong way this placeholder could
 * quietly win over the real implementation -- leaving a balance check that always answers
 * "settled" while money moves. Forgetting to delete a plain component instead fails the
 * context immediately with NoUniqueBeanDefinitionException, which is the right way for
 * this particular mistake to be noticed.
 */
@Component
public class AlwaysSettledBalanceProbe implements GroupBalanceProbe {

    @Override
    public long netBalancePaise(UUID groupId, UUID userId) {
        return 0L;
    }
}
