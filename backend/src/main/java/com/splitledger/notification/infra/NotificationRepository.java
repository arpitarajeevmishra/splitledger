package com.splitledger.notification.infra;

import com.splitledger.notification.domain.Notification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Newest first, which is the order the index on (user_id, created_at desc) serves. */
    List<Notification> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /** Everything still unread, for clearing the bell in one statement rather than N. */
    List<Notification> findAllByUserIdAndReadAtIsNull(UUID userId);

    /**
     * Fetched by id <b>and</b> owner, never by id alone.
     *
     * <p>A lookup by id followed by an ownership check is the same thing written in a way
     * that someone can later forget half of. This cannot return another person's
     * notification to be checked and rejected; it simply does not find it.
     */
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);
}
