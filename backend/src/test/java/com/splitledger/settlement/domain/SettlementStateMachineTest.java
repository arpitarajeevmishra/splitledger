package com.splitledger.settlement.domain;

import com.splitledger.common.payment.PaymentMethod;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every state against every transition (finding C17's acceptance criterion).
 *
 * <p>Written as a table rather than a test per case because the interesting failures are
 * the ones nobody thinks to write: a transition that happens to work from a state it was
 * never meant to, and which only shows up as a settled debt that was never paid. Enumerating
 * the whole matrix means the legal set is stated once, and anything outside it has to throw.
 */
class SettlementStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");

    private record Transition(String name, BiConsumer<Settlement, Instant> apply) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Transition> TRANSITIONS = List.of(
            new Transition("markPending", Settlement::markPending),
            new Transition("confirm", Settlement::confirm),
            new Transition("reject", Settlement::reject),
            new Transition("cancel", Settlement::cancel),
            new Transition("expire", Settlement::expire));

    /** The whole legal set, in one place, as "from --transition--> to". */
    private static boolean isLegal(SettlementStatus from, String transition,
            PaymentMethod method) {
        return switch (transition) {
            case "markPending" -> from == SettlementStatus.INITIATED;
            // CONFIRMED is included: confirming twice is a documented no-op, not an error.
            case "confirm" -> from == SettlementStatus.PENDING
                    || from == SettlementStatus.EXPIRED
                    || from == SettlementStatus.CONFIRMED;
            case "reject" -> from == SettlementStatus.PENDING;
            case "cancel" -> from == SettlementStatus.INITIATED
                    || (from == SettlementStatus.PENDING && !method.isGateway());
            case "expire" -> from == SettlementStatus.INITIATED
                    || from == SettlementStatus.PENDING;
            default -> throw new IllegalArgumentException(transition);
        };
    }

    private static Settlement inState(SettlementStatus target, PaymentMethod method) {
        Settlement settlement = Settlement.initiate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 30_000L, method, NOW);
        switch (target) {
            case INITIATED -> { }
            case PENDING -> settlement.markPending(NOW);
            case CONFIRMED -> {
                settlement.markPending(NOW);
                settlement.confirm(NOW);
            }
            case FAILED -> {
                settlement.markPending(NOW);
                settlement.reject(NOW);
            }
            case CANCELLED -> settlement.cancel(NOW);
            case EXPIRED -> settlement.expire(NOW);
        }
        assertThat(settlement.status()).isEqualTo(target);
        return settlement;
    }

    static Stream<Arguments> everyStateAndTransition() {
        return Stream.of(PaymentMethod.values()).flatMap(method ->
                Stream.of(SettlementStatus.values()).flatMap(status ->
                        TRANSITIONS.stream().map(transition ->
                                Arguments.of(method, status, transition))));
    }

    @ParameterizedTest(name = "{0}: {1} --{2}-->")
    @MethodSource("everyStateAndTransition")
    @DisplayName("every state against every transition")
    void theMatrix(PaymentMethod method, SettlementStatus from, Transition transition) {
        Settlement settlement = inState(from, method);

        if (isLegal(from, transition.name(), method)) {
            assertThatCode(() -> transition.apply().accept(settlement, NOW))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> transition.apply().accept(settlement, NOW))
                    .isInstanceOf(ApiException.class)
                    .extracting(ex -> ((ApiException) ex).code())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
            // A refused transition must leave the settlement exactly as it was.
            assertThat(settlement.status()).isEqualTo(from);
        }
    }

    // --- the rules the matrix encodes, stated once in their own right ------

    @Test
    void confirmingTwiceSucceedsAndChangesNothing() {
        Settlement settlement = inState(SettlementStatus.CONFIRMED, PaymentMethod.CASH);
        Instant firstConfirm = settlement.confirmedAt();

        settlement.confirm(NOW.plusSeconds(60));

        // A retried webhook is the same event arriving again; failing it would make a
        // correct client look broken, and moving the timestamp would rewrite history.
        assertThat(settlement.status()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(settlement.confirmedAt()).isEqualTo(firstConfirm);
    }

    @Test
    void aLateCaptureStillConfirmsAnExpiredSettlement() {
        Settlement settlement = inState(SettlementStatus.EXPIRED, PaymentMethod.RAZORPAY);

        settlement.confirm(NOW.plusSeconds(60));

        // The money is real; our deadline was bookkeeping. Refusing would leave a paid
        // debt looking unpaid.
        assertThat(settlement.status()).isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    void aGatewayPaymentCannotBeCancelledOnceAnOrderExists() {
        Settlement settlement = inState(SettlementStatus.PENDING, PaymentMethod.RAZORPAY);

        assertThatThrownBy(() -> settlement.cancel(NOW))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("gateway");
        assertThat(settlement.status()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    void aCashPaymentCanBeCancelledWhileItAwaitsConfirmation() {
        Settlement settlement = inState(SettlementStatus.PENDING, PaymentMethod.CASH);

        settlement.cancel(NOW);

        assertThat(settlement.status()).isEqualTo(SettlementStatus.CANCELLED);
    }

    @Test
    void anAbandonedSettlementExpiresBeforeAnyOrderExists() {
        Settlement settlement = inState(SettlementStatus.INITIATED, PaymentMethod.SANDBOX);

        // C17: without this edge it would sit in the list forever.
        settlement.expire(NOW);

        assertThat(settlement.status()).isEqualTo(SettlementStatus.EXPIRED);
    }

    // --- expiry deadlines --------------------------------------------------

    @Test
    void aCheckoutSessionExpiresInMinutesAndAHandshakeInDays() {
        Settlement gateway = Settlement.initiate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 30_000L, PaymentMethod.RAZORPAY, NOW);
        Settlement cash = Settlement.initiate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 30_000L, PaymentMethod.CASH, NOW);

        // An abandoned browser tab must not pin a debt; two people meeting up need longer.
        assertThat(gateway.expiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
        assertThat(cash.expiresAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60));
    }

    @Test
    void expiryIsInclusiveAtTheDeadline() {
        Settlement settlement = Settlement.initiate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 30_000L, PaymentMethod.RAZORPAY, NOW);

        assertThat(settlement.hasExpiredBy(NOW.plusSeconds(15 * 60).minusMillis(1))).isFalse();
        assertThat(settlement.hasExpiredBy(NOW.plusSeconds(15 * 60))).isTrue();
    }

    @Test
    void confirmedFailedAndCancelledAreTerminalButExpiredIsNot() {
        assertThat(SettlementStatus.CONFIRMED.isTerminal()).isTrue();
        assertThat(SettlementStatus.FAILED.isTerminal()).isTrue();
        assertThat(SettlementStatus.CANCELLED.isTerminal()).isTrue();
        // Because a late capture can still arrive.
        assertThat(SettlementStatus.EXPIRED.isTerminal()).isFalse();
    }

    // --- creation ----------------------------------------------------------

    @Test
    void youCannotSettleWithYourself() {
        UUID same = UUID.randomUUID();

        assertThatThrownBy(() -> Settlement.initiate(UUID.randomUUID(), same, same, 30_000L,
                PaymentMethod.CASH, NOW))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void aSettlementMustMoveSomething() {
        assertThatThrownBy(() -> Settlement.initiate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0L, PaymentMethod.CASH, NOW))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AMOUNT_OUT_OF_RANGE);
    }
}
