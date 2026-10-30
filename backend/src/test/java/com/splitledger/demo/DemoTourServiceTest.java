package com.splitledger.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.identity.application.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Letting a visitor in without an account (SL-904).
 */
@ExtendWith(MockitoExtension.class)
class DemoTourServiceTest {

    private static final String PASSWORD = "the-configured-demo-password";

    @Mock
    private AuthService auth;

    @Mock
    private AuthService.Session session;

    private DemoTourService tour() {
        return new DemoTourService(auth, PASSWORD);
    }

    @Test
    void signs_in_as_the_demo_account_through_the_ordinary_login() {
        when(auth.login(DemoSeed.OWNER_EMAIL, PASSWORD)).thenReturn(session);

        assertThat(tour().start()).isSameAs(session);

        // Through login(), not through a session-minting shortcut. A method that issued a
        // session for an arbitrary account would exist from then on, one refactor away
        // from taking its id from a request.
        verify(auth).login(eq(DemoSeed.OWNER_EMAIL), eq(PASSWORD));
    }

    @Test
    void reports_an_unseeded_deployment_as_unavailable_rather_than_as_bad_credentials() {
        when(auth.login(DemoSeed.OWNER_EMAIL, PASSWORD))
                .thenThrow(new ApiException(ErrorCode.INVALID_CREDENTIALS, "no"));

        // The visitor supplied no credential, so "email or password is incorrect" would be
        // a nonsense answer to give them. The password being configured while the account
        // is missing is an operator's problem, and it is logged as one.
        assertThatThrownBy(() -> tour().start())
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
