package com.splitledger.group.api;

import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.group.api.dto.CreateGroupRequest;
import com.splitledger.group.api.dto.GroupDetailResponse;
import com.splitledger.group.api.dto.GroupSummaryResponse;
import com.splitledger.group.application.GroupService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService groupService;

    GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupDetailResponse create(Authentication authentication,
            @Valid @RequestBody CreateGroupRequest request) {
        return groupService.create(AuthenticatedUser.require(authentication), request.name(),
                request.currency());
    }

    @GetMapping
    public List<GroupSummaryResponse> listMine(Authentication authentication) {
        return groupService.listMine(AuthenticatedUser.require(authentication));
    }

    @GetMapping("/{groupId}")
    public GroupDetailResponse detail(Authentication authentication,
            @PathVariable UUID groupId) {
        return groupService.detail(groupId, AuthenticatedUser.require(authentication));
    }
}
