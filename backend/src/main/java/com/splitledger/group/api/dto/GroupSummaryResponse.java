package com.splitledger.group.api.dto;

import com.splitledger.group.domain.Role;
import java.time.Instant;
import java.util.UUID;

public record GroupSummaryResponse(
        UUID id, String name, String currency, Role yourRole, int memberCount, Instant createdAt) {
}
