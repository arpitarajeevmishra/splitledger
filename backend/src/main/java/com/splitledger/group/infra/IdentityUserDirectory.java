package com.splitledger.group.infra;

import com.splitledger.group.application.UserDirectory;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The group module's one reach into identity, in a single class so the dependency is
 * easy to find and easy to replace. Read-only: groups never create or modify a user.
 */
@Component
class IdentityUserDirectory implements UserDirectory {

    private final UserRepository users;

    IdentityUserDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> userIdFor(String email) {
        return users.findByEmail(normaliseEmail(email)).map(User::id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> emailOf(UUID userId) {
        return users.findById(userId).map(User::email);
    }

    @Override
    public String normaliseEmail(String email) {
        return User.normaliseEmail(email);
    }
}
