package com.splitledger.identity.application;

/**
 * Where a verification link goes.
 *
 * <p>A port with one implementation that does not send mail, because sending it is SL-902:
 * a transactional provider, plus SPF, DKIM and DMARC on the domain, without which the mail
 * lands in spam and the application silently appears broken. Building half of that here
 * would be worse than building none of it -- a verification flow that looks finished and
 * delivers nothing is harder to notice than one that was never wired up.
 *
 * <p>Until then the mechanism is complete and the delivery is not, which is a real product
 * limitation and is written down as one.
 */
public interface VerificationMailer {

    /**
     * @param rawToken the only time this value exists outside the sender; it is never
     *     stored, so an implementation that drops it has lost it permanently
     */
    void send(String email, String rawToken);
}
