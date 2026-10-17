package com.splitledger.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-for-hs256!!";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private static JwtService serviceAt(Instant now) {
        return new JwtService(SECRET, Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuesATokenThatVerifiesBackToTheSameUser() {
        UUID userId = UUID.randomUUID();
        JwtService service = serviceAt(T0);

        String token = service.issueAccessToken(userId, "member@example.com");

        assertThat(service.verifyAndExtractUserId(token)).isEqualTo(userId);
    }

    @Test
    void rejectsATokenThatHasExpired() {
        JwtService issuer = serviceAt(T0);
        String token = issuer.issueAccessToken(UUID.randomUUID(), "member@example.com");

        // One second past the 15 minute TTL.
        JwtService later = serviceAt(T0.plus(Duration.ofMinutes(15)).plusSeconds(1));

        assertThatThrownBy(() -> later.verifyAndExtractUserId(token))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void stillAcceptsATokenJustInsideItsTtl() {
        JwtService issuer = serviceAt(T0);
        UUID userId = UUID.randomUUID();
        String token = issuer.issueAccessToken(userId, "member@example.com");

        JwtService later = serviceAt(T0.plus(Duration.ofMinutes(14)));

        assertThat(later.verifyAndExtractUserId(token)).isEqualTo(userId);
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        String token = serviceAt(T0).issueAccessToken(UUID.randomUUID(), "member@example.com");
        JwtService other = new JwtService("a-completely-different-secret-also-long-enough!",
                Duration.ofMinutes(15), Clock.fixed(T0, ZoneOffset.UTC));

        assertThatThrownBy(() -> other.verifyAndExtractUserId(token))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsATamperedPayload() {
        String token = serviceAt(T0).issueAccessToken(UUID.randomUUID(), "member@example.com");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];

        assertThatThrownBy(() -> serviceAt(T0).verifyAndExtractUserId(tampered))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsGarbageRatherThanThrowingSomethingUnhandled() {
        assertThatThrownBy(() -> serviceAt(T0).verifyAndExtractUserId("not-a-jwt"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void refusesASecretTooShortForHs256() {
        assertThatThrownBy(() -> new JwtService("too-short", Duration.ofMinutes(15),
                Clock.fixed(T0, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
