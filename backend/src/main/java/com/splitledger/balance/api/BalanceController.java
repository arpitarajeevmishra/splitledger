package com.splitledger.balance.api;

import com.splitledger.balance.api.dto.BalancesResponse;
import com.splitledger.balance.api.dto.SimplifiedPlanResponse;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.common.web.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/balances")
public class BalanceController {

    private final BalanceService balances;

    BalanceController(BalanceService balances) {
        this.balances = balances;
    }

    @GetMapping
    public BalancesResponse balances(Authentication authentication, @PathVariable UUID groupId) {
        return BalancesResponse.of(
                balances.of(groupId, AuthenticatedUser.require(authentication)));
    }

    /**
     * The fewest payments that settle the group.
     *
     * <p>Suggestions, but payable ones: a settlement is validated against net balances
     * (ADR-010), so a transfer taken straight from this plan is accepted even between two
     * people who never shared an expense.
     */
    @GetMapping("/simplified")
    public SimplifiedPlanResponse simplified(Authentication authentication,
            @PathVariable UUID groupId) {
        return SimplifiedPlanResponse.of(
                balances.simplified(groupId, AuthenticatedUser.require(authentication)));
    }
}
