package com.splitledger.notification.domain;

/**
 * What a notification is about.
 *
 * <p>The same vocabulary as {@code ActivityKind}, deliberately, and a separate enum just as
 * deliberately. The activity feed explains a group's history to someone looking at it; this
 * is what gets pushed at a person who is not looking. The two answer different questions and
 * will diverge -- a member joining belongs in a history and does not deserve a bell.
 */
public enum NotificationType {

    EXPENSE_ADDED,
    EXPENSE_VOIDED,
    SETTLEMENT_CONFIRMED
}
