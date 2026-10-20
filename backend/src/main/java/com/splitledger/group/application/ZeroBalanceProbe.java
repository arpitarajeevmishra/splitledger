package com.splitledger.group.application;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * Placeholder until the ledger exists (S4): reports everyone as settled.
 *
 * <p>Registered with {@code @ConditionalOnMissingBean} so the ledger's real implementation
 * replaces it simply by existing, with nothing to remember to delete. Until then removal
 * is never blocked on balance -- which is correct, because with no expenses nobody can
 * owe anything.
 */
@Configuration(proxyBeanMethods = false)
public class ZeroBalanceProbe {

    @Bean
    @ConditionalOnMissingBean(GroupBalanceProbe.class)
    GroupBalanceProbe zeroBalanceProbe() {
        return (groupId, userId) -> 0L;
    }
}
