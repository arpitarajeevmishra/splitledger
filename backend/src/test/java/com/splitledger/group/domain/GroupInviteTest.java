package com.splitledger.group.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GroupInviteTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);

    private static GroupInvite invite(String email) {
        return GroupInvite.issue(UUID.randomUUID(), email, UUID.randomUUID(), "hash", NOW,
                NOW.plus(WEEK));
    }

    @Test
    void emailIsNormalisedSoOneOpenInvitePerAddressCanBeEnforced() {
        // The partial unique index is on lower(email); storing it any other way would let
        // two open invites to the same person coexist.
        assertThat(invite("  Bob@Example.COM ").email()).isEqualTo("bob@example.com");
    }

    @Test
    void aFreshInviteIsPending() {
        GroupInvite invite = invite("bob@example.com");
        assertThat(invite.status()).isEqualTo(InviteStatus.PENDING);
        assertThat(invite.isUsable(NOW)).isTrue();
    }

    @Test
    void anInvitePastItsDateIsUnusableWithoutAnyoneHavingWrittenToIt() {
        GroupInvite invite = invite("bob@example.com");
        Instant later = NOW.plus(WEEK).plusSeconds(1);

        assertThat(invite.isUsable(later)).isFalse();
        assertThat(invite.effectiveStatus(later)).isEqualTo(InviteStatus.EXPIRED);
        // Derived, not stored: nothing had to update the row for it to read as expired.
        assertThat(invite.status()).isEqualTo(InviteStatus.PENDING);
    }

    @Test
    void expiryIsExclusiveAtTheBoundary() {
        GroupInvite invite = invite("bob@example.com");
        assertThat(invite.isUsable(NOW.plus(WEEK).minusMillis(1))).isTrue();
        assertThat(invite.isUsable(NOW.plus(WEEK))).isFalse();
    }

    @Test
    void answeredInvitesAreNoLongerUsable() {
        GroupInvite accepted = invite("bob@example.com");
        accepted.accept(NOW);
        assertThat(accepted.isUsable(NOW)).isFalse();
        assertThat(accepted.effectiveStatus(NOW)).isEqualTo(InviteStatus.ACCEPTED);

        GroupInvite declined = invite("bob@example.com");
        declined.decline(NOW);
        assertThat(declined.isUsable(NOW)).isFalse();

        GroupInvite revoked = invite("bob@example.com");
        revoked.revoke(NOW);
        assertThat(revoked.isUsable(NOW)).isFalse();
        // A revoked invite stays revoked even once its date has passed; expiry only
        // relabels one that was still pending.
        assertThat(revoked.effectiveStatus(NOW.plus(WEEK).plusSeconds(1)))
                .isEqualTo(InviteStatus.REVOKED);
    }
}
