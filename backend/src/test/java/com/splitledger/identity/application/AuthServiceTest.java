package com.splitledger.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.identity.domain.User;
import com.splitledger.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokens;

    private AuthService service(boolean registrationEnabled) {
        when(jwtService.issueAccessToken(any(), anyString())).thenReturn("an.access.token");
        when(jwtService.accessTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokens.issue(any())).thenReturn("a-refresh-token");
        return new AuthService(users, passwordEncoder, jwtService, refreshTokens,
                registrationEnabled, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void refusesToRegisterWhileRegistrationIsClosed() {
        assertThatThrownBy(() -> service(false).register("a@example.com", "A", "password123"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.REGISTRATION_DISABLED);

        verify(users, never()).save(any());
    }

    @Test
    void registersWithALowerCasedEmailAndAHashedPassword() {
        when(users.existsByEmail("member@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        AuthService.Session session =
                service(true).register("  Member@Example.COM ", " Rahul ", "password123");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().email()).isEqualTo("member@example.com");
        assertThat(saved.getValue().name()).isEqualTo("Rahul");
        assertThat(saved.getValue().passwordHash()).isEqualTo("hashed");
        assertThat(saved.getValue().createdAt()).isEqualTo(T0);
        assertThat(session.accessToken()).isEqualTo("an.access.token");
        assertThat(session.refreshToken()).isEqualTo("a-refresh-token");
    }

    @Test
    void rejectsADuplicateEmailWithA409() {
        when(users.existsByEmail("member@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service(true).register("Member@example.com", "A", "password123"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
        assertThat(ErrorCode.EMAIL_ALREADY_REGISTERED.status().value()).isEqualTo(409);
    }

    @Test
    void logsInWithCorrectCredentials() {
        User user = User.register("member@example.com", "Rahul", "hashed", T0);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);

        AuthService.Session session = service(true).login("Member@Example.com", "password123");

        assertThat(session.user().email()).isEqualTo("member@example.com");
        assertThat(session.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void rejectsAWrongPassword() {
        User user = User.register("member@example.com", "Rahul", "hashed", T0);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service(true).login("member@example.com", "wrong"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void stillVerifiesAHashWhenTheAccountDoesNotExist() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service(true).login("nobody@example.com", "password123"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        // The point of the dummy hash: an unknown address must not answer faster than a
        // wrong password, or response timing enumerates who has an account.
        verify(passwordEncoder).matches(eq("password123"), anyString());
    }

    @Test
    void logoutWithoutASessionIsASuccessAndTouchesNothing() {
        service(true).logout(null);
        service(true).logout("   ");

        verify(refreshTokens, never()).rotate(anyString());
        verify(refreshTokens, never()).revokeAll(any(UUID.class));
    }

    @Test
    void logoutSwallowsAnUnknownTokenRatherThanFailing() {
        when(refreshTokens.rotate("stale"))
                .thenThrow(new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));

        service(true).logout("stale");   // must not throw

        verify(refreshTokens, never()).revokeAll(any(UUID.class));
    }
}
