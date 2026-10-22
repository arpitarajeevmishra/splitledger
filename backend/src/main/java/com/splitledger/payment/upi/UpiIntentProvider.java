package com.splitledger.payment.upi;

import com.splitledger.common.errors.ApiException;
import com.splitledger.common.errors.ErrorCode;
import com.splitledger.payment.port.PaymentProvider;
import com.splitledger.payment.port.PaymentRequest;
import com.splitledger.payment.port.ProviderOrder;
import com.splitledger.common.payment.PaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds a {@code upi://pay} deep link for the payer's own UPI app.
 *
 * <p>No money passes through this application: the link opens someone's banking app, they
 * pay, and the receiver confirms it arrived like any cash settlement. The link is
 * generated here rather than in the browser because the payee's payment address is
 * involved, and constructing a payment URI is not something the frontend should be
 * improvising.
 *
 * <p><b>Off by default.</b> This is the only method in the product that can reach a real
 * bank account, which is exactly why `app.payments.upi-intent-enabled` exists and defaults
 * to false (ADR-013). Every figure in this product is fictional; a deep link that moves
 * real rupees against invented debts would not be.
 */
@Component
public class UpiIntentProvider implements PaymentProvider {

    private final boolean enabled;

    UpiIntentProvider(@Value("${app.payments.upi-intent-enabled}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.UPI_INTENT;
    }

    @Override
    public ProviderOrder initiate(PaymentRequest request) {
        if (!enabled) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "UPI payments are switched off in this deployment");
        }
        // There is no order to create -- the reference exists so the settlement has
        // something to record, and so the flow matches every other method's shape.
        return new ProviderOrder("upi_" + UUID.randomUUID(), null);
    }

    /**
     * The deep link itself, built once the payee's address is known.
     *
     * @param payeeUpiId the receiver's UPI address; only they can supply it, and the
     *     settlement module reads it from their profile
     */
    public String intentUri(String payeeUpiId, String payeeName, long amountPaise, UUID settlementId) {
        if (!enabled) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "UPI payments are switched off in this deployment");
        }
        // UPI expects rupees with two decimals. Converted from paise at the very edge,
        // for display in a URI, and never used for arithmetic (ADR-002).
        String amount = BigDecimal.valueOf(amountPaise, 2).setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
        return "upi://pay?pa=" + encode(payeeUpiId)
                + "&pn=" + encode(payeeName)
                + "&am=" + amount
                + "&cu=INR"
                + "&tn=" + encode("SplitLedger " + settlementId);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
