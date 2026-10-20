package com.splitledger.common.web;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * Reads the user id the JWT filter put in as the principal.
 *
 * <p>Read explicitly rather than through {@code @AuthenticationPrincipal} so a principal
 * of an unexpected type fails as a clear 401 instead of a confusing argument-resolution
 * error deep in Spring MVC.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static UUID require(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UUID userId) {
            return userId;
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }
}
