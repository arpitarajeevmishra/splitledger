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
    private final String razorpayKeyId;

    AppConfigController(
            @Value("${app.registration-enabled}") boolean registrationEnabled,
            @Value("${app.payments.upi-intent-enabled}") boolean upiIntentEnabled,
            @Value("${app.payments.razorpay.key-id:}") String razorpayKeyId) {
        this.registrationEnabled = registrationEnabled;
        this.upiIntentEnabled = upiIntentEnabled;
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
            String razorpayKeyId) {
    }

    @GetMapping
    public AppConfig config() {
        return new AppConfig(registrationEnabled, upiIntentEnabled, true, razorpayKeyId);
    }
}
