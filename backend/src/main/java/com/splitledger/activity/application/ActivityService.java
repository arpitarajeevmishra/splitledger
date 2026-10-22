package com.splitledger.activity.application;

import com.splitledger.activity.domain.ActivityEntry;
import com.splitledger.activity.domain.ActivityKind;
import com.splitledger.activity.infra.ActivityQueries;
import com.splitledger.group.application.GroupAccessGuard;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What happened in a group, newest first. */
@Service
public class ActivityService {

    /** Enough to fill a screen; a client wanting everything pages for it. */
    public static final int MAX_PAGE_SIZE = 100;

    private static final List<ActivityKind> MOVES_MONEY = List.of(
            ActivityKind.EXPENSE_ADDED,
            ActivityKind.EXPENSE_VOIDED,
            ActivityKind.SETTLEMENT_CONFIRMED);

    private final ActivityQueries queries;
    private final GroupAccessGuard guard;

    ActivityService(ActivityQueries queries, GroupAccessGuard guard) {
        this.queries = queries;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public List<ActivityEntry> feed(UUID groupId, UUID actorId, int page, int size) {
        guard.requireMember(groupId, actorId);

        int bounded = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * bounded;

        List<ActivityEntry> entries = new ArrayList<>();
        for (Object[] row : queries.feed(groupId, bounded, offset)) {
            ActivityKind kind = ActivityKind.valueOf((String) row[0]);
            entries.add(new ActivityEntry(
                    kind,
                    asUuid(row[1]),
                    asUuid(row[2]),
                    asInstant(row[3]),
                    (String) row[4],
                    asLongOrNull(row[5]),
                    asUuid(row[6]),
                    MOVES_MONEY.contains(kind)));
        }
        return entries;
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    /**
     * The driver may hand back a Timestamp or an OffsetDateTime depending on the column
     * and the query shape, so both are handled rather than cast and hoped for.
     */
    private static Instant asInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            default -> Instant.parse(value.toString());
        };
    }

    private static Long asLongOrNull(Object value) {
        return switch (value) {
            case null -> null;
            case BigInteger big -> big.longValueExact();
            case Number number -> number.longValue();
            default -> Long.parseLong(value.toString());
        };
    }
}
