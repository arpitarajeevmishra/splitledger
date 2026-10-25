package com.splitledger.group.api.dto;

import com.splitledger.group.domain.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * @param archived true when the group has been put away. Sent even on the active list, so
 *     a client never has to infer it from which endpoint the row arrived on.
 */
public record GroupSummaryResponse(
        UUID id, String name, String currency, Role yourRole, int memberCount, Instant createdAt,
        boolean archived) {
}
