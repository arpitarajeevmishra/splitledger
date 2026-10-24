package com.splitledger.payment.razorpay;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 the way Razorpay specifies it, and compared the way it has to be (SL-602).
 *
 * <p>Two rules, both of them the kind that look like detail and are not:
 *
 * <p><b>Constant time.</b> {@code String.equals} returns the moment two bytes differ, so
 * the time it takes reveals how many leading characters were right. That turns forging a
 * signature from guessing 2^256 values into guessing 64 hex characters one at a time,
 * which is minutes of work. {@link MessageDigest#isEqual} does not short-circuit.
 *
 * <p><b>The key is the API secret</b>, and the message is exactly {@code orderId|paymentId}
 * for a client callback -- not the other way round, not with a different separator. A
 * signature scheme that is nearly right verifies nothing at all.
 */
final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    /** The hex digest of {@code payload} under {@code secret}. */
    static String hmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("HmacSHA256 is required by the JDK", ex);
        } catch (InvalidKeyException ex) {
            // An empty or malformed secret. A configuration fault, not a bad callback:
            // answering "invalid signature" here would blame the caller for our mistake.
            throw new IllegalStateException("The Razorpay key secret is not a usable HMAC key",
                    ex);
        }
    }

    /**
     * Whether {@code presented} is the signature for {@code payload}.
     *
     * @param presented the value from the caller, which may be anything at all
     */
    static boolean matches(String payload, String secret, String presented) {
        if (presented == null) {
            return false;
        }
        byte[] expected = hmacHex(payload, secret).getBytes(StandardCharsets.UTF_8);
        // Trimmed and lower-cased because Razorpay sends lower-case hex and a client that
        // uppercases or pads it is not an attacker. This is not leniency about the secret:
        // producing any casing of the right digest still requires it.
        byte[] offered = presented.trim().toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8);
        // isEqual compares every byte even when the lengths differ, so a wrong-length
        // signature is rejected in the same time as a wrong one.
        return MessageDigest.isEqual(expected, offered);
    }

    /** The message Razorpay signs for a client callback: order id, pipe, payment id. */
    static String callbackPayload(String orderId, String paymentId) {
        return orderId + '|' + paymentId;
    }
}
