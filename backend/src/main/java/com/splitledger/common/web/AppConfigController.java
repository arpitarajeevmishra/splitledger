package com.splitledger.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment has switched on, for the client that has to render it.
 *
 * <p>Exists so the UI stops guessing. Hard-coding "UPI is off" in the frontend means the
 * two drift the moment someone enables it on the server: the method stays hidden, or
 * worse, a hidden method quietly becomes reachable. Reading the real flag means the
 * screen can only show what the server would actually accept.
 *
 * <p>Public and unauthenticated on purpose -- the sign-up page needs to know whether
 * registration is open before anyone has an account. Nothing here is a secret: these are
 * facts a visitor discovers within one click anyway.
 */
@RestController
@RequestMapping("/api/v1/config")
public class AppConfigController {

    private final boolean registrationEnabled;
    private final boolean upiIntentEnabled;
    private final boolean guestTourEnabled;
    private final String razorpayKeyId;

    AppConfigController(
            @Value("${app.registration-enabled}") boolean registrationEnabled,
            @Value("${app.payments.upi-intent-enabled}") boolean upiIntentEnabled,
            @Value("${app.demo.seed-password:}") String demoSeedPassword,
            @Value("${app.payments.razorpay.key-id:}") String razorpayKeyId) {
        this.registrationEnabled = registrationEnabled;
        this.upiIntentEnabled = upiIntentEnabled;
        // Derived from the same setting that creates the endpoint, rather than being a
        // flag of its own. Two settings could disagree, and the disagreement would either
        // offer a button that 404s or hide a tour that works -- the same reasoning as the
        // Razorpay key below.
        this.guestTourEnabled = demoSeedPassword != null && !demoSeedPassword.isBlank();
        // Blank becomes null, so "no key" is one value rather than two the client has to
        // test for separately.
        this.razorpayKeyId = razorpayKeyId == null || razorpayKeyId.isBlank()
                ? null
                : razorpayKeyId.trim();
    }

    /**
     * @param demoMode always true in this product, and sent rather than assumed so the
     *     banner has a flag to read instead of being unconditional markup someone could
     *     delete without noticing (ADR-013)
     * @param guestTourEnabled whether this deployment has demo data to show a visitor,
     *     which is true exactly when the seed password is set -- so the button cannot be
     *     offered by a deployment that would 404 on it
     * @param razorpayKeyId the public half of the key pair, which Checkout needs in the
     *     browser to open an order, or null when this deployment has no keys. One field
     *     rather than a key plus an "enabled" flag: two values can disagree, and the
     *     disagreement would either offer a card payment that 502s or hide one that works
     *     (SL-605).
     */
    public record AppConfig(
            boolean registrationEnabled,
            boolean upiIntentEnabled,
            boolean demoMode,
            boolean guestTourEnabled,
            String razorpayKeyId) {
    }

    @GetMapping
    public AppConfig config() {
        return new AppConfig(registrationEnabled, upiIntentEnabled, true, guestTourEnabled,
                razorpayKeyId);
    }
}
