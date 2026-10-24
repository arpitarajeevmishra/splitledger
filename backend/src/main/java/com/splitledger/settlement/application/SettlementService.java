package com.splitledger.settlement.application;

import com.splitledger.balance.application.BalanceService;
import com.splitledger.balance.domain.MemberBalance;
import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.common.money.Money;
import com.splitledger.group.application.GroupAccessGuard;
import com.splitledger.group.application.GroupLock;
import com.splitledger.group.domain.GroupMember;
import com.splitledger.group.infra.GroupMemberRepository;
import com.splitledger.settlement.domain.Settlement;
import com.splitledger.ledger.application.LedgerService;
import com.splitledger.payment.port.PaymentProvider;
import com.splitledger.payment.port.PaymentRequest;
import com.splitledger.payment.port.ProviderCallback;
import com.splitledger.payment.port.ProviderOrder;
import com.splitledger.ledger.domain.TransactionType;
import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.settlement.domain.SettlementStatus;
import com.splitledger.settlement.infra.SettlementRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating a settlement: one person declaring they are paying another.
 *
 * <p>Nothing is posted to the ledger here. Creating a settlement records an <em>intent</em>
 * to pay; the money only reaches the ledger when someone confirms it arrived (SL-503).
 * Posting at creation would let anyone clear a debt by announcing they had paid it.
 */
@Service
public class SettlementService {

    private final SettlementRepository settlements;
    private final GroupMemberRepository members;
    private final GroupAccessGuard guard;
    private final GroupLock groupLock;
    private final BalanceService balances;
    private final LedgerService ledger;
    private final List<PaymentProvider> providers;
    private final Clock clock;

    @Autowired
    SettlementService(SettlementRepository settlements, GroupMemberRepository members,
            GroupAccessGuard guard, GroupLock groupLock, BalanceService balances,
            LedgerService ledger, List<PaymentProvider> providers) {
        this(settlements, members, guard, groupLock, balances, ledger, providers,
                Clock.systemUTC());
    }

    /** Visible for testing. */
    SettlementService(SettlementRepository settlements, GroupMemberRepository members,
            GroupAccessGuard guard, GroupLock groupLock, BalanceService balances,
            LedgerService ledger, List<PaymentProvider> providers, Clock clock) {
        this.settlements = settlements;
        this.members = members;
        this.guard = guard;
        this.groupLock = groupLock;
        this.balances = balances;
        this.ledger = ledger;
        this.providers = providers;
        this.clock = clock;
    }

    /**
     * Record that {@code payer} intends to pay {@code payee}.
     *
     * <p>The payer is always the caller. Letting one person create a settlement on
     * another's behalf would let anyone mark their own debts as being paid by someone
     * else.
     */
    @Transactional
    public Settlement create(UUID groupId, UUID payerId, UUID payeeId, long amountPaise,
            PaymentMethod method) {
        guard.requireMember(groupId, payerId);
        groupLock.acquire(groupId);

        requireActiveMembers(groupId, payerId, payeeId);
        requireGatewayMinimum(method, amountPaise);
        requireWithinOutstanding(groupId, payerId, payeeId, amountPaise);

        return settlements.save(Settlement.initiate(groupId, payerId, payeeId, amountPaise,
                method, Instant.now(clock)));
    }

    /**
     * The payer says they have sent the money. Nothing is posted yet -- only the receiver
     * saying it arrived moves the ledger.
     */
    @Transactional
    public Settlement markPaid(UUID settlementId, UUID actorId) {
        Settlement settlement = lockedForAction(settlementId, actorId);
        requirePayer(settlement, actorId, "Only the payer can mark this as paid");

        settlement.markPending(Instant.now(clock));
        return settlements.save(settlement);
    }

    /**
     * The receiver says the money arrived, which is what posts it to the ledger.
     *
     * <p>Idempotent: confirming an already-confirmed settlement succeeds and posts
     * nothing. A receiver tapping twice and a retried request are the same event, and the
     * ledger's unique index on (type, source_id) would refuse the second posting anyway --
     * this returns success rather than surfacing that as an error.
     */
    @Transactional
    public Settlement confirm(UUID settlementId, UUID actorId) {
        Settlement settlement = lockedForAction(settlementId, actorId);
        requirePayee(settlement, actorId, "Only the person being paid can confirm it arrived");

        if (settlement.status() == SettlementStatus.CONFIRMED) {
            return settlement;
        }

        // Re-checked here, not just at creation: two settlements could each have been
        // valid when started and together exceed the debt (LLD 7.2). Whichever confirms
        // second is the one that is now too much.
        requireWithinOutstanding(settlement.groupId(), settlement.fromUser(),
                settlement.toUser(), settlement.amountPaise());

        settlement.confirm(Instant.now(clock));
        settlements.save(settlement);

        // The payer laid out money, so the group owes them that much more; the receiver
        // has been made whole by the same amount (LLD 5.1).
        ledger.post(settlement.groupId(), TransactionType.SETTLEMENT, settlement.id(), List.of(
                LedgerService.Line.of(settlement.fromUser(), settlement.amountPaise()),
                LedgerService.Line.of(settlement.toUser(), -settlement.amountPaise())));
        return settlement;
    }

    /** The receiver says it never arrived. */
    @Transactional
    public Settlement reject(UUID settlementId, UUID actorId) {
        Settlement settlement = lockedForAction(settlementId, actorId);
        requirePayee(settlement, actorId, "Only the person being paid can reject it");

        settlement.reject(Instant.now(clock));
        return settlements.save(settlement);
    }

    /** The payer calls it off. */
    @Transactional
    public Settlement cancel(UUID settlementId, UUID actorId) {
        Settlement settlement = lockedForAction(settlementId, actorId);
        requirePayer(settlement, actorId, "Only the payer can cancel this");

        settlement.cancel(Instant.now(clock));
        return settlements.save(settlement);
    }

    /**
     * Start taking payment through a provider.
     *
     * <p>Idempotent by state rather than by key: if an order already exists this returns
     * it instead of creating a second one. A client that retries after a dropped response
     * must not end up with two orders against one debt, and the provider's own reference
     * is the natural thing to key on (LLD 8).
     */
    @Transactional
    public Settlement createProviderOrder(UUID settlementId, UUID actorId) {
        Settlement settlement = lockedForAction(settlementId, actorId);
        requirePayer(settlement, actorId, "Only the payer can start this payment");

        if (settlement.gatewayOrderId() != null) {
            return settlement;
        }
        if (!settlement.method().isGateway()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This payment method has no order to create");
        }

        ProviderOrder order = providerFor(settlement.method()).initiate(
                new PaymentRequest(settlement.id(), settlement.amountPaise(), "INR"));
        settlement.recordGatewayOrder(order.orderId());
        settlement.markPending(Instant.now(clock));
        return settlements.save(settlement);
    }

    /**
     * The payer's browser reports that a gateway checkout finished (SL-602).
     *
     * <p>Everything in the callback is attacker-controlled: it is an ordinary authenticated
     * request carrying three strings a page handed over. Two things have to hold before any
     * of it is believed, and the order matters.
     *
     * <p>First the signature, asked of the provider -- only it can tell whether the gateway
     * really said these words. Then the order id, compared against the one stored when the
     * order was created: a signature proves Razorpay said something, not that it said it
     * about this debt. Without that comparison, anyone holding a genuine callback for their
     * own ten-rupee payment could post it against someone else's ten-thousand-rupee
     * settlement, and every check would pass.
     *
     * <p>Nothing is written until both hold, so a tampered callback leaves the settlement
     * exactly as it was.
     */
    @Transactional
    public Settlement confirmFromCallback(UUID settlementId, UUID actorId, String orderId,
            String paymentId, String signature) {
        Settlement settlement = lockedForAction(settlementId, actorId);
        requirePayer(settlement, actorId, "Only the payer can complete this payment");

        if (settlement.status() == SettlementStatus.CONFIRMED) {
            // The result page reloaded, or the browser posted twice. Already-confirmed is
            // the answer, not an error -- and re-posting must not post to the ledger again.
            return settlement;
        }
        if (settlement.gatewayOrderId() == null) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This payment has no gateway order to complete");
        }

        String verifiedPaymentId = providerFor(settlement.method())
                .verifiedPaymentId(new ProviderCallback(orderId, paymentId, signature))
                .orElseThrow(() -> new ApiException(ErrorCode.PAYMENT_SIGNATURE_INVALID,
                        "That payment could not be verified"));

        if (!settlement.gatewayOrderId().equals(orderId)) {
            // Same error as a bad signature, deliberately: a caller probing with genuine
            // callbacks should not learn which of the two checks they failed.
            throw new ApiException(ErrorCode.PAYMENT_SIGNATURE_INVALID,
                    "That payment could not be verified");
        }

        return confirmFromProvider(settlementId, verifiedPaymentId);
    }

    /**
     * Confirmation that came from the provider rather than from the receiver.
     *
     * <p>Deliberately skips the payee check that {@link #confirm} enforces: for a gateway
     * method the provider is the authority on whether money moved, and the receiver has no
     * say. There is no HTTP route to this -- callers are the sandbox's own scheduler, the
     * client callback, and the webhook handler, all of which have verified a signature.
     *
     * <p>It also skips the net-balance re-check, which is a narrowing of ADR-010 and the
     * one judgement call in SL-604. ADR-010 re-checks at confirm time so that two
     * settlements, each valid when started, cannot both complete and overpay the debt.
     * That reasoning is sound where "completing" means a human asserting the money arrived
     * -- refusing the second one costs nobody anything, and {@link #confirm} still does it.
     *
     * <p>A gateway capture is not an assertion. The money has already left the payer. The
     * question is no longer "may this be authorised" but "what shall we do with money that
     * has arrived", and refusing it does not send it back: it loses the payment, leaves the
     * settlement PENDING, and answers the provider with a 4xx it will retry forever.
     * Observed, rather than reasoned about -- a debt settled in cash, then captured by the
     * gateway anyway, produced a 422 on every delivery and no record of the money at all.
     *
     * <p>So it posts, and the payer ends up in credit: a reverse debt the group owes them.
     * ADR-010 already calls that "a legal, visible reverse debt, not an error" for the
     * overpayment that voiding an expense creates. This is the same thing arriving by a
     * different route, and the alternative is an application that quietly keeps money it
     * cannot account for.
     */
    @Transactional
    public Settlement confirmFromProvider(UUID settlementId, String providerPaymentId) {
        Settlement settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such settlement"));
        groupLock.acquire(settlement.groupId());

        if (settlement.status() == SettlementStatus.CONFIRMED) {
            return settlement;
        }

        settlement.recordGatewayPayment(providerPaymentId);
        settlement.confirm(Instant.now(clock));
        settlements.save(settlement);

        ledger.post(settlement.groupId(), TransactionType.SETTLEMENT, settlement.id(), List.of(
                LedgerService.Line.of(settlement.fromUser(), settlement.amountPaise()),
                LedgerService.Line.of(settlement.toUser(), -settlement.amountPaise())));
        return settlement;
    }

    private PaymentProvider providerFor(PaymentMethod method) {
        return providers.stream()
                .filter(provider -> provider.method() == method)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.GATEWAY_ERROR,
                        "No provider is configured for " + method));
    }

    /**
     * Loads a settlement for a state change, with the group locked first.
     *
     * <p>Group first, then the row: confirming posts to the ledger and re-reads balances,
     * and both have to be inside the lock that serialises the group's money writes.
     */
    private Settlement lockedForAction(UUID settlementId, UUID actorId) {
        UUID groupId = settlements.findGroupIdById(settlementId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such settlement"));
        guard.requireMember(groupId, actorId);
        groupLock.acquire(groupId);

        // Loaded after the lock so its status is the committed one, not a snapshot taken
        // while someone else was still mid-transition (D-83).
        return settlements.findById(settlementId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such settlement"));
    }

    private static void requirePayer(Settlement settlement, UUID actorId, String message) {
        if (!settlement.fromUser().equals(actorId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, message);
        }
    }

    private static void requirePayee(Settlement settlement, UUID actorId, String message) {
        if (!settlement.toUser().equals(actorId)) {
            // The receiver confirms, never the payer: otherwise anyone could settle their
            // own debts by paying nothing and confirming it themselves.
            throw new ApiException(ErrorCode.FORBIDDEN, message);
        }
    }

    @Transactional(readOnly = true)
    public List<Settlement> list(UUID groupId, UUID actorId) {
        guard.requireMember(groupId, actorId);
        return settlements.findAllByGroupIdOrderByCreatedAtDesc(groupId);
    }

    @Transactional(readOnly = true)
    public Settlement detail(UUID settlementId, UUID actorId) {
        Settlement settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such settlement"));
        // Via the group, so a stranger gets the same 404 as for a group that is not theirs
        // rather than confirming the settlement id is real.
        guard.requireMember(settlement.groupId(), actorId);
        return settlement;
    }

    /**
     * The ADR-010 rule: the limit is the <b>net</b> balance, never the pairwise debt.
     *
     * <p>This is the whole reason simplification is usable. A simplified transfer is
     * deliberately a payment between two people who often have no direct debt at all --
     * A owes B, B owes C, so the plan says A pays C. Validating against the pairwise
     * figure would reject every one of those, which is to say it would reject exactly the
     * payments the feature exists to suggest.
     *
     * <p>Pending settlements are deliberately <em>not</em> subtracted from the limit. Two
     * people could each start one and together exceed the debt, so confirmation re-runs
     * this same check and the second one fails there (SL-503). The trade-off is accepted
     * because holding a debt against an unconfirmed intent would let anyone block a
     * group's settlements by starting one and walking away.
     */
    private void requireWithinOutstanding(UUID groupId, UUID payerId, UUID payeeId,
            long amountPaise) {
        Map<UUID, Long> net = new HashMap<>();
        for (MemberBalance balance : balances.netOnly(groupId, payerId)) {
            net.put(balance.userId(), balance.net().paise());
        }

        long payerNet = net.getOrDefault(payerId, 0L);
        long payeeNet = net.getOrDefault(payeeId, 0L);

        if (payerNet >= 0L) {
            throw new ApiException(ErrorCode.SETTLEMENT_EXCEEDS_OUTSTANDING,
                    "You do not owe this group anything");
        }
        if (payeeNet <= 0L) {
            throw new ApiException(ErrorCode.SETTLEMENT_EXCEEDS_OUTSTANDING,
                    "They are not owed anything by this group");
        }

        long limit = Math.min(-payerNet, payeeNet);
        if (amountPaise > limit) {
            throw new ApiException(ErrorCode.SETTLEMENT_EXCEEDS_OUTSTANDING,
                    "That is more than the " + Money.ofPaise(limit) + " outstanding between you");
        }
    }

    /**
     * Refuse an amount the chosen provider would refuse, here rather than at the gateway
     * (LLD 7.4, finding C5).
     *
     * <p>The floor comes from the method, which both this module and the payment module
     * can see. It used to be a constant here next to a test for {@code == RAZORPAY}, which
     * put Razorpay's commercial terms in the settlement module and the same figure in two
     * places once the provider needed it too.
     */
    private void requireGatewayMinimum(PaymentMethod method, long amountPaise) {
        long minimum = method.minimumPaise();
        if (amountPaise < minimum) {
            throw new ApiException(ErrorCode.SETTLEMENT_BELOW_GATEWAY_MINIMUM,
                    "Card payments start at " + Money.ofPaise(minimum));
        }
    }

    private void requireActiveMembers(UUID groupId, UUID payerId, UUID payeeId) {
        Set<UUID> active = new HashSet<>();
        for (GroupMember member : members.findAllByIdGroupIdAndLeftAtIsNull(groupId)) {
            active.add(member.userId());
        }
        if (!active.contains(payerId) || !active.contains(payeeId)) {
            // A debt to someone who has left is settled by re-inviting them, not by paying
            // into a group they are no longer part of.
            throw new ApiException(ErrorCode.PARTICIPANT_NOT_MEMBER,
                    "Both people must be active members of this group");
        }
    }
}
