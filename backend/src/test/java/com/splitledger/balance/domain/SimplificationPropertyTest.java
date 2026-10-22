package com.splitledger.balance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The properties a settlement plan has to have, for every set of balances rather than the
 * handful anyone would think to write down.
 */
class SimplificationPropertyTest {

    /**
     * Arbitrary balances that sum to zero, which is the only input simplification accepts
     * and the only input a real ledger produces. Built by giving everyone a random
     * position and making the last person absorb the difference.
     */
    @Provide
    Arbitrary<Map<UUID, Long>> balancedGroups() {
        return Arbitraries.integers().between(2, 30).flatMap(count ->
                Arbitraries.longs().between(-5_000_000L, 5_000_000L)
                        .list().ofSize(count - 1)
                        .map(amounts -> {
                            Map<UUID, Long> net = new LinkedHashMap<>();
                            long running = 0L;
                            for (long amount : amounts) {
                                net.put(UUID.randomUUID(), amount);
                                running += amount;
                            }
                            net.put(UUID.randomUUID(), -running);
                            return net;
                        }));
    }

    private static Map<UUID, Long> applyTo(Map<UUID, Long> net, List<Transfer> transfers) {
        Map<UUID, Long> after = new HashMap<>(net);
        for (Transfer transfer : transfers) {
            // Paying reduces what you are owed... or, when negative, clears what you owe.
            after.merge(transfer.from(), transfer.amount().paise(), Long::sum);
            after.merge(transfer.to(), -transfer.amount().paise(), Long::sum);
        }
        return after;
    }

    @Property(tries = 2000)
    void applyingThePlanSettlesEveryone(@ForAll("balancedGroups") Map<UUID, Long> net) {
        Map<UUID, Long> after = applyTo(net, Simplification.of(net));

        // The whole promise: do these transfers and nobody owes anybody.
        assertThat(after.values()).allSatisfy(balance -> assertThat(balance).isZero());
    }

    @Property(tries = 2000)
    void atMostOneFewerTransfersThanPeopleWhoOweOrAreOwed(
            @ForAll("balancedGroups") Map<UUID, Long> net) {
        long unsettled = net.values().stream().filter(balance -> balance != 0L).count();
        List<Transfer> transfers = Simplification.of(net);

        if (unsettled == 0L) {
            assertThat(transfers).isEmpty();
        } else {
            // Every transfer clears at least one person, so this is the bound.
            assertThat(transfers).hasSizeLessThanOrEqualTo((int) unsettled - 1);
        }
    }

    @Property(tries = 2000)
    void everyTransferIsAPositiveAmountBetweenTwoDifferentPeople(
            @ForAll("balancedGroups") Map<UUID, Long> net) {
        for (Transfer transfer : Simplification.of(net)) {
            assertThat(transfer.amount().isPositive()).isTrue();
            // A transfer to yourself would settle nothing and look like a bug to whoever
            // was asked to make it.
            assertThat(transfer.from()).isNotEqualTo(transfer.to());
        }
    }

    @Property(tries = 2000)
    void nobodyPaysMoreThanTheyOweAndNobodyReceivesMoreThanTheyAreOwed(
            @ForAll("balancedGroups") Map<UUID, Long> net) {
        Map<UUID, Long> paid = new HashMap<>();
        Map<UUID, Long> received = new HashMap<>();
        for (Transfer transfer : Simplification.of(net)) {
            paid.merge(transfer.from(), transfer.amount().paise(), Long::sum);
            received.merge(transfer.to(), transfer.amount().paise(), Long::sum);
        }

        net.forEach((userId, balance) -> {
            // Asking someone to pay more than they owe is how a "simplification" quietly
            // becomes a transfer of wealth.
            assertThat(paid.getOrDefault(userId, 0L))
                    .isEqualTo(balance < 0L ? -balance : 0L);
            assertThat(received.getOrDefault(userId, 0L))
                    .isEqualTo(balance > 0L ? balance : 0L);
        });
    }

    @Property(tries = 1000)
    void theSamePlanIsProducedEveryTime(@ForAll("balancedGroups") Map<UUID, Long> net) {
        // Two people looking at their screens must see the same suggestion, so the
        // tie-break has to be total.
        assertThat(Simplification.of(net))
                .isEqualTo(Simplification.of(new HashMap<>(net)));
    }

    @Property(tries = 500)
    void aSettledGroupNeedsNoTransfers(@ForAll("balancedGroups") Map<UUID, Long> net) {
        Map<UUID, Long> settled = new LinkedHashMap<>();
        net.keySet().forEach(userId -> settled.put(userId, 0L));

        assertThat(Simplification.of(settled)).isEmpty();
    }

    @Property(tries = 1000)
    void theTotalMovedEqualsTheTotalOwed(@ForAll("balancedGroups") Map<UUID, Long> net) {
        long owed = net.values().stream().filter(balance -> balance < 0L)
                .mapToLong(balance -> -balance).sum();
        long moved = Simplification.of(net).stream()
                .mapToLong(transfer -> transfer.amount().paise()).sum();

        // No money invented, none lost.
        assertThat(moved).isEqualTo(owed);
    }

    @Property(tries = 500)
    void transfersAreNotInventedForPeopleAlreadySettled(
            @ForAll("balancedGroups") Map<UUID, Long> net) {
        Map<UUID, Long> withSpectators = new LinkedHashMap<>(net);
        List<UUID> spectators = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID spectator = UUID.randomUUID();
            spectators.add(spectator);
            withSpectators.put(spectator, 0L);
        }

        List<Transfer> transfers = Simplification.of(withSpectators);

        // Someone who owes nothing should not be asked to move money around for others.
        assertThat(transfers).allSatisfy(transfer -> {
            assertThat(spectators).doesNotContain(transfer.from());
            assertThat(spectators).doesNotContain(transfer.to());
        });
    }
}
