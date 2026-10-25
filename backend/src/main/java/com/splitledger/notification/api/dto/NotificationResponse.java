package com.splitledger.notification.api.dto;

import com.splitledger.notification.domain.Notification;
import com.splitledger.notification.domain.NotificationType;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/**
 * @param message the frozen payload, passed through as JSON rather than re-typed here: it
 *     is already the shape the client needs, and re-declaring its fields in this record
 *     would mean two places to change every time a notification learns to say something
 *     new
 * @param readAt null while it is still unread, which is also what the bell counts
 */
public record NotificationResponse(UUID id, NotificationType type, JsonNode message,
        Instant readAt, Instant createdAt) {

    public static NotificationResponse of(Notification notification, ObjectMapper json) {
        return new NotificationResponse(notification.id(), notification.type(),
                json.readTree(notification.payload()), notification.readAt(),
                notification.createdAt());
    }
}
