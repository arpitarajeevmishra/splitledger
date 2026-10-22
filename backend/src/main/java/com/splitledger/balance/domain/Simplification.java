package com.splitledger.balance.domain;

import com.splitledger.common.money.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Fewest payments that settle a group (LLD 6.3).
 *
 * <p>Four people who each owe one other person can settle in three transfers instead of
 * six, and nobody has to care who originally paid for what. Repeatedly match the largest
 * creditor against the largest debtor and move the smaller of the two amounts: that
 * clears at least one person per transfer, so {@code n} people with a non-zero balance
 * need at most {@code n - 1} transfers.
 *
 * <p>Greedy, and deliberately not optimal. Finding the true minimum is NP-hard, and the
 * difference in practice is a transfer or two in a group of a dozen people. Worth being
 * honest about rather than claiming "minimum".
 *
 * <p>These are suggestions, but <b>payable</b> ones: settlements validate against net
 * balances rather than pairwise debt (ADR-010), so a transfer taken straight from this
 * plan is accepted even when the two people never shared an expense. Validating pairwise
 * would reject exactly the transfers simplification exists to create.
 */
public final class Simplification {

    private Simplification() {
    }

    private record Node(UUID userId, long amount) {
    }

    /**
     * @param net each member's position; positive means the group owes them
     * @return transfers that bring every balance to zero, at most {@code n - 1} of them
     */
    public static List<Transfer> of(Map<UUID, Long> net) {
        long sum = 0L;
        for (long balance : net.values()) {
            sum = Math.addExact(sum, balance);
        }
        if (sum != 0L) {
            // Not an ApiException: balances that do not sum to zero mean the ledger is
            // broken, not that a user asked for something wrong. Simplifying them would
            // produce a plan that cannot settle anything.
            throw new IllegalArgumentException(
                    "Balances must sum to zero before they can be simplified, but summed to "
                            + sum);
        }

        // Largest first, ties broken by id so the same balances always produce the same
        // plan -- two people comparing their screens should see the same suggestion.
        Comparator<Node> largestFirst =
                Comparator.comparingLong(Node::amount).reversed().thenComparing(Node::userId);
        PriorityQueue<Node> creditors = new PriorityQueue<>(largestFirst);
        PriorityQueue<Node> debtors = new PriorityQueue<>(largestFirst);

        net.forEach((userId, balance) -> {
            if (balance > 0L) {
                creditors.add(new Node(userId, balance));
            } else if (balance < 0L) {
                debtors.add(new Node(userId, -balance));
            }
        });

        List<Transfer> transfers = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Node creditor = creditors.poll();
            Node debtor = debtors.poll();
            long amount = Math.min(creditor.amount(), debtor.amount());

            transfers.add(new Transfer(debtor.userId(), creditor.userId(),
                    Money.ofPaise(amount)));

            // Whichever side is not cleared goes back with what is left. At least one of
            // them is cleared every time, which is what bounds this at n - 1.
            if (creditor.amount() > amount) {
                creditors.add(new Node(creditor.userId(), creditor.amount() - amount));
            }
            if (debtor.amount() > amount) {
                debtors.add(new Node(debtor.userId(), debtor.amount() - amount));
            }
        }
        return transfers;
    }
}
