package com.splitledger.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes as soon as a commit lands, so the poll can be slow (SL-701, amended).
 *
 * <p>The poll used to be the only path, at one second forever. That is a query per second
 * against a managed Postgres which suspends when idle and bills by the hour it stays
 * awake -- so an application nobody was using kept its own database awake indefinitely.
 * Slowing the poll alone would have traded that for notifications arriving a quarter of an
 * hour late.
 *
 * <p>So the commit does the work and the poll becomes a backstop for the one thing a nudge
 * cannot survive: a process that died between the commit and the publish. Nothing about
 * the durability argument changes -- the row is still written in the business transaction,
 * and still republished until it is stamped.
 *
 * <p>There is no cost to waking the database here, because it is already awake: a nudge
 * only happens immediately after something committed to it.
 */
@Component
public class OutboxNudge {

    private static final Logger log = LoggerFactory.getLogger(OutboxNudge.class);

    /** One nudge per transaction, however many events it recorded. */
    private static final String ALREADY_SCHEDULED = OutboxNudge.class.getName() + ".scheduled";

    private final ObjectProvider<OutboxPublisher> publisher;
    private final TaskExecutor executor;
    private final boolean enabled;

    OutboxNudge(ObjectProvider<OutboxPublisher> publisher, TaskExecutor executor,
            @Value("${app.outbox.nudge-enabled:true}") boolean enabled) {
        this.publisher = publisher;
        this.executor = executor;
        this.enabled = enabled;
    }

    /**
     * Ask for a publish once this transaction commits.
     *
     * <p>{@code ObjectProvider} because the publisher is {@code @Profile("!consumer")} and
     * the consumer role has none: on that role the nudge does nothing and the consumer's
     * own poll is the path, which is what the profile split is for.
     *
     * <p>Off the calling thread, so a handler cannot make somebody's HTTP request wait for
     * notifications to be written -- and so a failure here cannot fail a business
     * transaction that has already committed. A nudge that does not happen costs latency
     * until the backstop poll, and nothing else.
     */
    public void afterCommit() {
        // Off, and the publisher is then the poll alone -- which is correct, just slower.
        // A real switch rather than a test affordance: if the nudge ever misbehaves, the
        // fallback is the design that existed before it.
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || Boolean.TRUE.equals(TransactionSynchronizationManager
                        .getResource(ALREADY_SCHEDULED))) {
            return;
        }
        TransactionSynchronizationManager.bindResource(ALREADY_SCHEDULED, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(
                                ALREADY_SCHEDULED);
                    }

                    @Override
                    public void afterCommit() {
                        publisher.ifAvailable(outbox -> executor.execute(() -> {
                            try {
                                outbox.publishBatch();
                            } catch (RuntimeException failure) {
                                // The row is still there and still unstamped; the backstop
                                // poll will take it. Logged rather than swallowed because
                                // a nudge that always fails is a poll-shaped latency
                                // problem nobody would otherwise notice.
                                log.warn("outbox: nudge failed, leaving it to the poll ({})",
                                        failure.toString());
                            }
                        }));
                    }
                });
    }
}
