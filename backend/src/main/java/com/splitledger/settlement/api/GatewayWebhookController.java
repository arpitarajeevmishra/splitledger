package com.splitledger.settlement.api;

import com.splitledger.common.payment.PaymentMethod;
import com.splitledger.payment.port.ProviderWebhook;
import com.splitledger.settlement.application.GatewayWebhookService;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway webhook deliveries (SL-603, LLD 7.4).
 *
 * <p>Outside {@code /api/v1} and outside JWT on purpose: the caller is Razorpay's server,
 * which has no account here and never will. Its signature is the authentication, and the
 * path is already open in {@code SecurityConfig} for exactly this.
 *
 * <p>The body is taken as {@code byte[]}. That is not an optimisation: the signature covers
 * the octets as sent, so binding to a DTO and re-serialising would produce a different
 * document and a MAC that could never match. The bytes reach the provider untouched.
 *
 * <p>Named for the gateway in general rather than for Razorpay, because the arch rule that
 * keeps the gateway inside the payment module applies to class names too (D-105) -- and
 * because a second provider would arrive as another path here, not another controller.
 */
@RestController
@RequestMapping("/webhooks")
public class GatewayWebhookController {

    private final GatewayWebhookService webhooks;

    GatewayWebhookController(GatewayWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /**
     * Always 200 once the signature holds, whatever the delivery turned out to mean.
     *
     * <p>A provider retries anything that is not 2xx, so answering 4xx for "already seen"
     * or "nothing to do here" would buy an endless redelivery of an event that is already
     * settled or irrelevant. The only 4xx is an unverifiable delivery, which should be
     * retried, and which this application wants to hear about again.
     */
    @PostMapping("/razorpay")
    public Map<String, String> razorpay(@RequestBody(required = false) byte[] body,
            @RequestHeader HttpHeaders headers) {
        GatewayWebhookService.Outcome outcome = webhooks.handle(PaymentMethod.RAZORPAY,
                new ProviderWebhook(body, lowerCased(headers)));
        return Map.of("status", outcome.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Headers flattened to lower-case names, so the payment module can look up the ones it
     * cares about without this module naming them -- and without depending on whatever
     * casing a proxy decided to use.
     */
    private static Map<String, String> lowerCased(HttpHeaders headers) {
        Map<String, String> flat = new HashMap<>();
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                flat.put(name.toLowerCase(Locale.ROOT), values.getFirst());
            }
        });
        return flat;
    }
}
