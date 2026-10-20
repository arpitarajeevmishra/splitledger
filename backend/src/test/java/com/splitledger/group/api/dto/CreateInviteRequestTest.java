package com.splitledger.group.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class CreateInviteRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void trimsSoAPastedAddressIsNotRejectedAsMalformed() {
        CreateInviteRequest request = new CreateInviteRequest("  bob@example.com ");

        assertThat(request.email()).isEqualTo("bob@example.com");
        // @Email rejects surrounding whitespace, and the person pasting cannot see it.
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void leavesCaseAlone() {
        // Lowercasing belongs to InviteService, where it matches the lower(email) index.
        assertThat(new CreateInviteRequest("Bob@Example.COM").email())
                .isEqualTo("Bob@Example.COM");
    }

    @Test
    void stillRejectsSomethingThatIsNotAnAddress() {
        assertThat(validator.validate(new CreateInviteRequest(" not-an-email "))).isNotEmpty();
    }

    @Test
    void stillRejectsBlank() {
        assertThat(validator.validate(new CreateInviteRequest("   "))).isNotEmpty();
    }
}
