package com.splitledger.group.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.security.SecureTokens;
import com.splitledger.group.domain.ExpenseGroup;
import com.splitledger.group.domain.GroupInvite;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.domain.InviteStatus;
import com.splitledger.group.domain.Role;
import com.splitledger.group.infra.GroupInviteRepository;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.group.infra.GroupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InviteServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final String BASE_URL = "https://app.example.com";

    @Mock
    private GroupInviteRepository invites;
    @Mock
    private GroupRepository groups;
    @Mock
    private GroupMemberRepository members;
    @Mock
    private GroupAccessGuard guard;
    @Mock
    private GroupMembershipService memberships;

    /**
     * Hand-written rather than mocked: the email-matching rules are the whole point of
     * these tests, and a stub that really maps addresses to ids reads better than four
     * lines of {@code when(...)} per case.
     */
    private static final class FakeDirectory implements UserDirectory {
        private final Map<String, UUID> byEmail = new HashMap<>();
        private final Map<UUID, String> byId = new HashMap<>();
        private final java.util.Set<UUID> unverified = new java.util.HashSet<>();

        UUID add(String email) {
            UUID id = UUID.randomUUID();
            byEmail.put(normaliseEmail(email), id);
            byId.put(id, email);
            return id;
        }

        /** Verified by default: these cases are about the email rules, not about SL-901. */
        UUID addUnverified(String email) {
            UUID id = add(email);
            unverified.add(id);
            return id;
        }

        @Override
        public Optional<UUID> userIdFor(String email) {
            return Optional.ofNullable(byEmail.get(normaliseEmail(email)));
        }

        @Override
        public Optional<String> emailOf(UUID userId) {
            return Optional.ofNullable(byId.get(userId));
        }

        @Override
        public String normaliseEmail(String email) {
            return email.trim().toLowerCase();
        }

        @Override
        public boolean isEmailVerified(UUID userId) {
            return byId.containsKey(userId) && !unverified.contains(userId);
        }
    }

    private FakeDirectory directory;
    private InviteService service;
    private UUID groupId;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        directory = new FakeDirectory();
        groupId = UUID.randomUUID();
        adminId = directory.add("alice@example.com");
        service = new InviteService(invites, groups, members, guard, memberships, directory, TTL,
                BASE_URL, Clock.fixed(NOW, ZoneOffset.UTC));
        when(invites.save(any(GroupInvite.class))).thenAnswer(call -> call.getArgument(0));
    }

    private GroupInvite pendingInvite(String email, String rawToken) {
        return GroupInvite.issue(groupId, email, adminId, SecureTokens.hash(rawToken), NOW,
                NOW.plus(TTL));
    }

    private void tokenResolvesTo(String rawToken, GroupInvite invite) {
        when(invites.findByTokenHash(SecureTokens.hash(rawToken))).thenReturn(Optional.of(invite));
    }

    // --- creating ----------------------------------------------------------

    @Test
    void onlyAnAdminCanInvite() {
        when(guard.requireAdmin(groupId, adminId))
                .thenThrow(new ApiException(ErrorCode.FORBIDDEN, "nope"));

        assertThatThrownBy(() -> service.invite(groupId, adminId, "bob@example.com"))
                .isInstanceOf(ApiException.class);
        verify(invites, never()).save(any());
    }

    @Test
    void theRawTokenIsReturnedInTheLinkAndOnlyItsHashIsStored() {
        InviteService.Issued issued = service.invite(groupId, adminId, "bob@example.com");

        assertThat(issued.url()).startsWith(BASE_URL + "/invite/");
        String rawToken = issued.url().substring((BASE_URL + "/invite/").length());
        assertThat(rawToken).isNotBlank().matches("[A-Za-z0-9_-]+");

        ArgumentCaptor<GroupInvite> saved = ArgumentCaptor.forClass(GroupInvite.class);
        verify(invites).save(saved.capture());
        // The link works, and nothing recoverable from the database does.
        assertThat(saved.getValue().isUsable(NOW)).isTrue();
        assertThat(saved.getValue().expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(saved.getValue().matchesToken(SecureTokens.hash(rawToken))).isTrue();
        // The point of the exercise: what is stored is the hash, so the raw token out of
        // the link does not itself match anything in the database.
        assertThat(saved.getValue().matchesToken(rawToken)).isFalse();
    }

    @Test
    void theInvitedEmailIsNormalisedSoTheRecipientMatchesHowevertheyTypedIt() {
        service.invite(groupId, adminId, "  Bob@Example.COM ");

        ArgumentCaptor<GroupInvite> saved = ArgumentCaptor.forClass(GroupInvite.class);
        verify(invites).save(saved.capture());
        assertThat(saved.getValue().email()).isEqualTo("bob@example.com");
    }

    @Test
    void invitingSomeoneAlreadyInTheGroupIsRejected() {
        UUID bobId = directory.add("bob@example.com");
        when(members.findByIdGroupIdAndIdUserId(groupId, bobId))
                .thenReturn(Optional.of(GroupMember.join(groupId, bobId, Role.MEMBER, NOW)));

        assertThatThrownBy(() -> service.invite(groupId, adminId, "bob@example.com"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.ALREADY_GROUP_MEMBER);
        verify(invites, never()).save(any());
    }

    @Test
    void someoneWhoLeftCanBeInvitedBack() {
        UUID bobId = directory.add("bob@example.com");
        GroupMember departed = GroupMember.join(groupId, bobId, Role.MEMBER, NOW);
        departed.leave(NOW);
        when(members.findByIdGroupIdAndIdUserId(groupId, bobId)).thenReturn(Optional.of(departed));

        assertThat(service.invite(groupId, adminId, "bob@example.com").url()).isNotBlank();
    }

    @Test
    void anUnregisteredAddressCanBeInvited() {
        // Invitations go to people, not to accounts; the recipient signs up afterwards.
        assertThat(service.invite(groupId, adminId, "stranger@example.com").url()).isNotBlank();
    }

    @Test
    void reInvitingRevokesTheOpenInviteSoOnlyTheNewLinkWorks() {
        GroupInvite open = pendingInvite("bob@example.com", "old-token");
        when(invites.findByGroupIdAndEmailAndStatus(groupId, "bob@example.com",
                InviteStatus.PENDING)).thenReturn(Optional.of(open));

        service.invite(groupId, adminId, "bob@example.com");

        assertThat(open.status()).isEqualTo(InviteStatus.REVOKED);
        // The revocation must reach the database before the replacement is inserted, or
        // uq_invite_open rejects the new row. Asserted as an ordering rather than a count,
        // because the order is the invariant.
        InOrder order = inOrder(invites);
        order.verify(invites).flush();
        order.verify(invites).save(argThat(saved -> saved.status() == InviteStatus.PENDING));
    }

    @Test
    void losingTheRaceToInviteTheSamePersonIsAConflictNotACrash() {
        // uq_invite_open allows one open invite per (group, email). Two admins acting at
        // once both get past the pre-check, so the index is the real arbiter.
        doThrow(new DataIntegrityViolationException("uq_invite_open")).when(invites).flush();

        assertThatThrownBy(() -> service.invite(groupId, adminId, "bob@example.com"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_ALREADY_PENDING);
    }

    // --- accepting ---------------------------------------------------------

    @Test
    void acceptingJoinsAsAMemberAndMarksTheInviteAccepted() {
        UUID bobId = directory.add("bob@example.com");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);

        assertThat(service.accept("tok", bobId)).isEqualTo(groupId);

        assertThat(invite.status()).isEqualTo(InviteStatus.ACCEPTED);
        // MEMBER, never ADMIN: accepting an invitation must not confer administration.
        verify(memberships).joinOrRejoin(groupId, bobId, Role.MEMBER);
    }

    @Test
    void anUnverifiedAccountCannotAcceptAnInvitationToItsOwnAddress() {
        // The hole SL-901 closes, stated as the attack. Registering bob@example.com proves
        // nothing about owning that mailbox, so matching the invitation's address -- which
        // this account does, exactly -- is not evidence of anything. Without the
        // verification check, whoever registers the address first reads the group's
        // members, expenses and balances.
        UUID impostorId = directory.addUnverified("bob@example.com");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);

        assertThatThrownBy(() -> service.accept("tok", impostorId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);

        // And nothing happened: not joined, and the invitation is still there for whoever
        // can actually read that mailbox.
        verify(memberships, never()).joinOrRejoin(any(), any(), any());
        assertThat(invite.status()).isEqualTo(InviteStatus.PENDING);
    }

    @Test
    void anUnverifiedAccountCanStillDeclineAnInvitation() {
        // Declining joins nothing and reveals nothing, and somebody who cannot verify
        // should still be able to stop being offered the link.
        UUID bobId = directory.addUnverified("bob@example.com");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);

        service.decline("tok", bobId);

        assertThat(invite.status()).isEqualTo(InviteStatus.DECLINED);
    }

    @Test
    void acceptingMatchesTheAddressHoweverTheUserTypedItWhenRegistering() {
        UUID bobId = directory.add("Bob@Example.COM");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);

        assertThat(service.accept("tok", bobId)).isEqualTo(groupId);
    }

    @Test
    void someoneElseHoldingTheLinkCannotAcceptIt() {
        UUID carolId = directory.add("carol@example.com");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);

        assertThatThrownBy(() -> service.accept("tok", carolId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_EMAIL_MISMATCH);
        // FR-10: nobody joins a group they were not invited to, link or no link.
        verify(memberships, never()).joinOrRejoin(any(), any(), any());
        assertThat(invite.status()).isEqualTo(InviteStatus.PENDING);
    }

    @Test
    void anUnknownTokenIsIndistinguishableFromAnExpiredOne() {
        UUID bobId = directory.add("bob@example.com");
        when(invites.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept("never-issued", bobId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_INVALID);
    }

    @Test
    void anExpiredInviteCannotBeAccepted() {
        UUID bobId = directory.add("bob@example.com");
        GroupInvite invite = GroupInvite.issue(groupId, "bob@example.com", adminId,
                SecureTokens.hash("tok"), NOW.minus(TTL).minusSeconds(1), NOW.minusSeconds(1));
        tokenResolvesTo("tok", invite);

        assertThatThrownBy(() -> service.accept("tok", bobId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_INVALID);
        verify(memberships, never()).joinOrRejoin(any(), any(), any());
    }

    @Test
    void aRevokedInviteCannotBeAccepted() {
        UUID bobId = directory.add("bob@example.com");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        invite.revoke(NOW);
        tokenResolvesTo("tok", invite);

        assertThatThrownBy(() -> service.accept("tok", bobId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_INVALID);
    }

    @Test
    void anInviteCannotBeAcceptedTwice() {
        UUID bobId = directory.add("bob@example.com");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);

        service.accept("tok", bobId);

        // Single use: the first acceptance took it out of PENDING.
        assertThatThrownBy(() -> service.accept("tok", bobId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_INVALID);
        verify(memberships).joinOrRejoin(eq(groupId), eq(bobId), eq(Role.MEMBER));
    }

    // --- declining ---------------------------------------------------------

    @Test
    void decliningMarksTheInviteAndJoinsNothing() {
        UUID bobId = directory.add("bob@example.com");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);

        service.decline("tok", bobId);

        assertThat(invite.status()).isEqualTo(InviteStatus.DECLINED);
        verify(memberships, never()).joinOrRejoin(any(), any(), any());
    }

    @Test
    void someoneElseCannotBurnAnInviteByDecliningIt() {
        UUID carolId = directory.add("carol@example.com");
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);

        assertThatThrownBy(() -> service.decline("tok", carolId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_EMAIL_MISMATCH);
        assertThat(invite.status()).isEqualTo(InviteStatus.PENDING);
    }

    // --- revoking and reading ----------------------------------------------

    @Test
    void anAdminOfAnotherGroupCannotRevokeThisGroupsInvite() {
        UUID otherGroupId = UUID.randomUUID();
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        when(invites.findById(invite.id())).thenReturn(Optional.of(invite));

        // The guard passes -- they really do administer otherGroupId -- so the id alone
        // is not enough; the invite has to belong to the group in the path.
        assertThatThrownBy(() -> service.revoke(otherGroupId, invite.id(), adminId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_INVALID);
        assertThat(invite.status()).isEqualTo(InviteStatus.PENDING);
    }

    @Test
    void anAnsweredInviteCannotBeRevoked() {
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        invite.accept(NOW);
        when(invites.findById(invite.id())).thenReturn(Optional.of(invite));

        assertThatThrownBy(() -> service.revoke(groupId, invite.id(), adminId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void revokingAPendingInviteStopsTheLinkWorking() {
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        when(invites.findById(invite.id())).thenReturn(Optional.of(invite));

        service.revoke(groupId, invite.id(), adminId);

        assertThat(invite.status()).isEqualTo(InviteStatus.REVOKED);
        assertThat(invite.isUsable(NOW)).isFalse();
    }

    @Test
    void previewNamesTheGroupSoTheLandingPageCanSayWhatTheInviteIsFor() {
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        tokenResolvesTo("tok", invite);
        when(groups.findById(groupId)).thenReturn(Optional.of(
                ExpenseGroup.create("Flat 402", "INR", adminId, NOW)));

        InviteService.Preview preview = service.preview("tok");

        assertThat(preview.groupName()).isEqualTo("Flat 402");
        assertThat(preview.email()).isEqualTo("bob@example.com");
        assertThat(preview.expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void previewOfARevokedInviteRevealsNothing() {
        GroupInvite invite = pendingInvite("bob@example.com", "tok");
        invite.revoke(NOW);
        tokenResolvesTo("tok", invite);

        assertThatThrownBy(() -> service.preview("tok"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVITE_INVALID);
    }
}
