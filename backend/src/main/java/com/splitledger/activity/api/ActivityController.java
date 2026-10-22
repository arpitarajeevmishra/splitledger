package com.splitledger.activity.api;

import com.splitledger.activity.api.dto.ActivityResponse;
import com.splitledger.activity.application.ActivityService;
import com.splitledger.common.web.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/activity")
public class ActivityController {

    private final ActivityService activity;

    ActivityController(ActivityService activity) {
        this.activity = activity;
    }

    /**
     * Everything that happened in the group, newest first.
     *
     * <p>Derived from the expenses, settlements and memberships themselves rather than a
     * log written beside them, so it cannot disagree with the balances it explains.
     */
    @GetMapping
    public List<ActivityResponse> feed(Authentication authentication,
            @PathVariable UUID groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return activity.feed(groupId, AuthenticatedUser.require(authentication), page, size)
                .stream()
                .map(ActivityResponse::of)
                .toList();
    }
}
