package com.splitledger.notification.api;

import com.splitledger.common.web.AuthenticatedUser;
import com.splitledger.notification.api.dto.NotificationResponse;
import com.splitledger.notification.application.NotificationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The bell (SL-702).
 *
 * <p>No user id anywhere in the path or the query. Every method reads the caller's own id
 * from their token, so there is no version of these endpoints that can be pointed at
 * somebody else's notifications -- the same reasoning as {@code /balances/me}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;
    private final ObjectMapper json;

    NotificationController(NotificationService notifications, ObjectMapper json) {
        this.notifications = notifications;
        this.json = json;
    }

    @GetMapping
    public List<NotificationResponse> mine(Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return notifications.mine(AuthenticatedUser.require(authentication), page, size).stream()
                .map(notification -> NotificationResponse.of(notification, json))
                .toList();
    }

    /**
     * Separate from the list, because a bell badge is polled and the list is not.
     *
     * <p>Fetching thirty rows to render the number "3" would be the obvious alternative and
     * would make the cheapest, most frequent call the most expensive one.
     */
    @GetMapping("/unread-count")
    public Map<String, Long> unread(Authentication authentication) {
        return Map.of("unread", notifications.unread(AuthenticatedUser.require(authentication)));
    }

    @PostMapping("/{notificationId}/read")
    public NotificationResponse read(Authentication authentication,
            @PathVariable UUID notificationId) {
        return NotificationResponse.of(
                notifications.markRead(notificationId, AuthenticatedUser.require(authentication)),
                json);
    }

    /** @return how many were still unread, so a client can tell a no-op from work done */
    @PostMapping("/read")
    public Map<String, Integer> readAll(Authentication authentication) {
        return Map.of("cleared",
                notifications.markAllRead(AuthenticatedUser.require(authentication)));
    }
}
