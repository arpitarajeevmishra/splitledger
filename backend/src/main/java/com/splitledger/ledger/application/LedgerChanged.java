package com.splitledger.ledger.application;

import java.util.UUID;

/**
 * A group's postings moved.
 *
 * <p>Published by the ledger and listened for by whoever derives something from postings.
 * A Spring application event rather than a method call, so the ledger gains no dependency
 * on the things that care: {@code balance} reads postings, and a compile-time edge back the
 * other way would be a cycle that ArchUnit refuses -- correctly, because the ledger's whole
 * discipline is that it does not know why anything was posted (ADR-003).
 *
 * <p>Published <em>inside</em> the writing transaction on purpose. That is what lets a
 * listener ask for {@code AFTER_COMMIT}: the event has to be registered while a transaction
 * is still active for the phase to mean anything, and a listener that fired immediately
 * would evict a cache before the data it is about to be repopulated from exists.
 */
public record LedgerChanged(UUID groupId) {
}
