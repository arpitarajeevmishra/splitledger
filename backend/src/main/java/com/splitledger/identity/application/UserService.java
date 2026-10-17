package com.splitledger.identity.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile reads and edits for the authenticated user. */
@Service
public class UserService {

    private final UserRepository users;

    UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public User requireById(UUID userId) {
        return users.findById(userId)
                // A valid token for a deleted account: authenticated, but there is nothing
                // to show. 404 rather than 401, because the credential was fine.
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such user"));
    }

    /**
     * Applies only the fields that were supplied. Email and password are not editable here
     * by construction -- the request type has no component for either.
     */
    @Transactional
    public User updateProfile(UUID userId, String name, String upiId) {
        User user = requireById(userId);
        if (name != null) {
            user.rename(name.trim());
        }
        if (upiId != null) {
            // Blank means "clear it", which is different from absent meaning "leave it".
            user.changeUpiId(upiId.isBlank() ? null : upiId.trim());
        }
        return users.save(user);
    }
}
