package com.splitledger.identity.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdateProfileRequestTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    @Test
    void cannotCarryAnEmailOrPasswordAtAll() {
        List<String> components = Arrays.stream(UpdateProfileRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // Changing either needs its own flow -- re-verification for the email, the current
        // password for the password. Absence from the type is what guarantees this endpoint
        // cannot change them, rather than a service remembering not to.
        assertThat(components).containsExactlyInAnyOrder("name", "upiId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"rahul@oksbi", "rahul.kumar@okaxis", "r-k_1@okhdfcbank", ""})
    void acceptsRealisticPaymentAddressesAndTheEmptyStringThatClearsThem(String upiId) {
        assertThat(VALIDATOR.validate(new UpdateProfileRequest(null, upiId))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nohandle", "@oksbi", "a@", "spaces in@oksbi", "x@y"})
    void rejectsAddressesThatAreNotHandleAtPsp(String upiId) {
        assertThat(VALIDATOR.validate(new UpdateProfileRequest(null, upiId))).isNotEmpty();
    }

    @Test
    void acceptsAnAbsentUpiIdBecauseAbsentMeansUnchanged() {
        assertThat(VALIDATOR.validate(new UpdateProfileRequest("Rahul", null))).isEmpty();
    }

    @Test
    void rejectsAnEmptyNameSinceBlankIsNotAnEdit() {
        assertThat(VALIDATOR.validate(new UpdateProfileRequest("", null))).isNotEmpty();
    }
}
