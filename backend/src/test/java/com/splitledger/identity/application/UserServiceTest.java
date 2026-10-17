package com.splitledger.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock
    private UserRepository users;

    private UserService service;
    private User existing;

    @BeforeEach
    void setUp() {
        service = new UserService(users);
        existing = User.register("member@example.com", "Rahul", "hashed",
                Instant.parse("2026-01-01T10:00:00Z"));
        existing.changeUpiId("rahul@oksbi");
        when(users.findById(existing.id())).thenReturn(Optional.of(existing));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void returnsTheProfileIncludingTheOwnersOwnUpiId() {
        User found = service.requireById(existing.id());

        assertThat(found.email()).isEqualTo("member@example.com");
        assertThat(found.upiId()).isEqualTo("rahul@oksbi");
    }

    @Test
    void aValidTokenForADeletedAccountIsNotFoundRatherThanUnauthenticated() {
        UUID gone = UUID.randomUUID();
        when(users.findById(gone)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireById(gone))
                .isInstanceOf(ApiException.class)
                // The credential was valid; there is simply nothing to show.
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void updatesOnlyTheNameWhenOnlyTheNameIsSupplied() {
        User updated = service.updateProfile(existing.id(), "  Rahul K  ", null);

        assertThat(updated.name()).isEqualTo("Rahul K");
        assertThat(updated.upiId()).isEqualTo("rahul@oksbi");   // untouched
    }

    @Test
    void updatesOnlyTheUpiIdWhenOnlyTheUpiIdIsSupplied() {
        User updated = service.updateProfile(existing.id(), null, "rahul@okaxis");

        assertThat(updated.name()).isEqualTo("Rahul");           // untouched
        assertThat(updated.upiId()).isEqualTo("rahul@okaxis");
    }

    @Test
    void blankClearsTheUpiIdWhileAbsentLeavesIt() {
        // The distinction is the whole of PATCH semantics: "" means remove, null means
        // "I am not editing this field".
        assertThat(service.updateProfile(existing.id(), null, "").upiId()).isNull();

        existing.changeUpiId("rahul@oksbi");
        assertThat(service.updateProfile(existing.id(), null, null).upiId())
                .isEqualTo("rahul@oksbi");
    }

    @Test
    void neverChangesTheEmail() {
        service.updateProfile(existing.id(), "New Name", "new@okhdfcbank");

        // Not a defensive check but a structural one: UpdateProfileRequest has no email
        // component, so no call into this service can carry one.
        assertThat(existing.email()).isEqualTo("member@example.com");
    }
}
