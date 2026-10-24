package com.splitledger.payment.port;

import com.splitledger.common.payment.PaymentMethod;
import java.time.Instant;
import java.util.Optional;

/**
 * How money is taken, behind one interface (ADR-006).
 *
 * <p>The settlement module asks for an order and never learns which provider answered.
 * That is what keeps Razorpay's SDK confined to this package -- an ArchUnit rule enforces
 * it -- and what lets the demo run on a sandbox no external service backs.
 *
 * <p>The dependency runs one way: {@code settlement} uses this port, and nothing here
 * knows what a settlement is. It takes a {@link PaymentRequest} this module owns and
 * returns ids; interpreting them is the caller's business.
 *
 * <p>Deliberately narrow for now. Webhook parsing and client-callback verification arrive
 * with the real gateway in S7 -- an interface with methods nobody implements yet is a
 * promise the code has not made.
 */
public interface PaymentProvider {

    /** Which method this provider answers for. */
    PaymentMethod method();

    /**
     * Begin taking payment.
     *
     * <p>Called once per settlement; the caller stores the order id and will not ask
     * again, so a provider need not be idempotent itself.
     */
    ProviderOrder initiate(PaymentRequest request);

    /**
     * Has this order been paid?
     *
     * <p>For polling providers only. A real gateway pushes a webhook instead and has
     * nothing to say here, so the default is "no news" -- which is also the honest answer
     * for one that has genuinely not been paid.
     *
     * @param orderedAt when the order was created, for providers whose answer depends on
     *     elapsed time
     * @return the provider's payment id once the money has moved, otherwise empty
     */
    default Optional<String> completedPaymentId(String orderId, Instant orderedAt) {
        return Optional.empty();
    }

    /**
     * Is this callback genuinely from the provider, about this order?
     *
     * <p>The payer's browser is told the payment succeeded and posts that claim here. The
     * claim is worth nothing on its own -- it comes from a page anyone can replay or edit --
     * so a provider that can prove it answers with the payment id, and one that cannot
     * answers empty.
     *
     * <p>Empty by default, which refuses the callback. That is the safe direction: a
     * provider with no way to verify a browser's word must not be able to confirm a
     * settlement by accident. The sandbox has none, and reaches the same end by polling.
     *
     * @return the provider's payment id when the signature holds, otherwise empty
     */
    default Optional<String> verifiedPaymentId(ProviderCallback callback) {
        return Optional.empty();
    }

    /**
     * Is this webhook delivery genuinely from the provider, and what does it say?
     *
     * <p>Unlike a client callback, a webhook arrives from the provider's own servers with
     * no user session behind it, and it is the authority on whether money moved: it is
     * delivered whether or not the payer's browser ever came back. Which is exactly why it
     * is also the most attractive thing on this application to forge, and why the raw
     * bytes are passed through unparsed -- the signature covers those octets and nothing
     * else.
     *
     * <p>Empty by default, so a provider with no webhook scheme cannot be driven by an
     * unauthenticated POST. Same direction as everything else here: a provider that cannot
     * verify must not be able to confirm.
     *
     * @return what the delivery says, once the signature over the raw body holds
     */
    default Optional<ProviderEvent> verifiedEvent(ProviderWebhook webhook) {
        return Optional.empty();
    }
}
