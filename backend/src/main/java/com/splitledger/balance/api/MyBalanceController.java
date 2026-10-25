package com.splitledger.balance.api;

import com.splitledger.balance.api.dto.NetPositionResponse;
import com.splitledger.balance.application.BalanceService;
import com.splitledger.common.web.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the caller stands across all their groups.
 *
 * <p>Separate from {@link BalanceController} because it is not scoped to a group, and its
 * authorisation is different in kind: there is no group to be a member of, and the only
 * subject is whoever holds the token. The id comes from the authentication and never from
 * the path, which is what stops this being an endpoint for reading other people's
 * finances.
 */
@RestController
@RequestMapping("/api/v1/balances")
public class MyBalanceController {

    private final BalanceService balances;

    MyBalanceController(BalanceService balances) {
        this.balances = balances;
    }

    @GetMapping("/me")
    public NetPositionResponse mine(Authentication authentication) {
        return NetPositionResponse.of(
                balances.netAcrossGroups(AuthenticatedUser.require(authentication)));
    }
}
