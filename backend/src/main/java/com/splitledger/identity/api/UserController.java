package com.splitledger.identity.api;

import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.identity.api.dto.SelfProfileResponse;
import com.splitledger.identity.api.dto.UpdateProfileRequest;
import com.splitledger.identity.application.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The profile endpoints act on the authenticated user and take no user id in the path.
 * That is the authorisation model, not a convenience: with no id to supply there is no
 * id to tamper with, so the commonest way to leak or edit someone else's profile cannot
 * be expressed as a request.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public SelfProfileResponse me(Authentication authentication) {
        return SelfProfileResponse.from(userService.requireById(AuthenticatedUser.require(authentication)));
    }

    @PatchMapping("/me")
    public SelfProfileResponse updateMe(Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request) {
        return SelfProfileResponse.from(userService.updateProfile(
                AuthenticatedUser.require(authentication), request.name(), request.upiId()));
    }

}
