package com.splitledger.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.notification.domain.Notification;
import com.splitledger.notification.domain.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-10-25T09:00:00Z");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    @Mock
    private com.splitledger.notification.infra.NotificationRepository notifications;

    private NotificationService service() {
        return new NotificationService(notifications, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Notification mine() {
        return Notification.of(ALICE, NotificationType.EXPENSE_ADDED, "{}", EARLIER);
    }

    @Test
    void marking_one_read_stamps_it() {
        Notification notification = mine();
        when(notifications.findByIdAndUserId(notification.id(), ALICE))
                .thenReturn(Optional.of(notification));
        when(notifications.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service().markRead(notification.id(), ALICE).readAt()).isEqualTo(NOW);
    }

    @Test
    void somebody_else_s_notification_is_not_found_rather_than_forbidden() {
        Notification notification = mine();
        // The repository is asked for id AND owner together, so Bob's request simply does
        // not match a row. 403 would confirm that a notification with this id exists and
        // belongs to someone, which is more than guessing an id should ever reveal.
        when(notifications.findByIdAndUserId(notification.id(), BOB))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().markRead(notification.id(), BOB))
                .isInstanceOf(ApiException.class)
                .satisfies(failure ->
                        assertThat(((ApiException) failure).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void reading_an_already_read_notification_does_not_move_the_receipt() {
        Notification notification = mine();
        notification.markRead(EARLIER);
        when(notifications.findByIdAndUserId(notification.id(), ALICE))
                .thenReturn(Optional.of(notification));
        when(notifications.save(any())).thenAnswer(call -> call.getArgument(0));

        // Two tabs, a retry, a double tap: clearing a bell is a gesture a client will send
        // twice, and the second one must not rewrite when it was first seen.
        assertThat(service().markRead(notification.id(), ALICE).readAt()).isEqualTo(EARLIER);
    }

    @Test
    void clearing_the_bell_reports_how_much_it_actually_cleared() {
        when(notifications.findAllByUserIdAndReadAtIsNull(ALICE))
                .thenReturn(List.of(mine(), mine()));

        // The count is what lets a client tell "cleared two" from "there was nothing to
        // clear", which are the same screen otherwise.
        assertThat(service().markAllRead(ALICE)).isEqualTo(2);
    }

    @Test
    void clearing_an_empty_bell_is_not_an_error() {
        when(notifications.findAllByUserIdAndReadAtIsNull(ALICE)).thenReturn(List.of());

        assertThat(service().markAllRead(ALICE)).isZero();
    }

    @Test
    void a_caller_cannot_ask_for_an_unbounded_page() {
        service().mine(ALICE, 0, 10_000);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(notifications)
                .findAllByUserIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.eq(ALICE),
                        page.capture());
        // A bell is a recent-things list. Without the clamp, "size=1000000" is a request
        // for every notification the account has ever received, served in one response.
        assertThat(page.getValue().getPageSize()).isEqualTo(NotificationService.MAX_PAGE_SIZE);
    }

    @Test
    void a_negative_page_is_read_as_the_first_one() {
        service().mine(ALICE, -3, 20);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(notifications)
                .findAllByUserIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.eq(ALICE),
                        page.capture());
        // PageRequest.of throws on a negative page, so this is the difference between a
        // clamp and a 500.
        assertThat(page.getValue().getPageNumber()).isZero();
    }
}
