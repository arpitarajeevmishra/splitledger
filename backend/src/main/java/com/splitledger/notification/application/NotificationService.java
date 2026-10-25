package com.splitledger.notification.application;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.notification.domain.Notification;
import com.splitledger.notification.infra.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bell (SL-702).
 *
 * <p>Every method takes the reader's own id, taken from their token by the controller and
 * never from the request. There is no group to authorise against here: a notification
 * belongs to one person, so ownership <em>is</em> the authorisation, and the repository
 * fetches by id and owner together rather than fetching by id and checking afterwards.
 */
@Service
public class NotificationService {

    /** A bell is a recent-things list, not an archive; the client paginates from here. */
    public static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notifications;
    private final Clock clock;

    @Autowired
    NotificationService(NotificationRepository notifications) {
        this(notifications, Clock.systemUTC());
    }

    /** Visible for testing. */
    NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Notification> mine(UUID userId, int page, int size) {
        return notifications.findAllByUserIdOrderByCreatedAtDesc(userId,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    @Transactional(readOnly = true)
    public long unread(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /**
     * Reading someone else's notification is a 404, not a 403.
     *
     * <p>403 would confirm that a notification with that id exists and belongs to someone,
     * which is more than a stranger should learn from guessing an id. The group endpoints
     * already answer this way for the same reason.
     */
    @Transactional
    public Notification markRead(UUID notificationId, UUID userId) {
        Notification notification = notifications.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such notification"));
        notification.markRead(Instant.now(clock));
        return notifications.save(notification);
    }

    /**
     * Clear the bell.
     *
     * <p>Here rather than left to the client as one call per row: clearing twenty
     * notifications should not be twenty round trips, and a client that gave up halfway
     * would leave a count that disagrees with what the person just did.
     *
     * @return how many were still unread, so the caller can tell a no-op from work
     */
    @Transactional
    public int markAllRead(UUID userId) {
        Instant now = Instant.now(clock);
        List<Notification> unread =
                notifications.findAllByUserIdAndReadAtIsNull(userId);
        unread.forEach(notification -> notification.markRead(now));
        notifications.saveAll(unread);
        return unread.size();
    }
}
