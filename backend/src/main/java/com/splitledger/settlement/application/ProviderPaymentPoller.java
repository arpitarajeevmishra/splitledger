package com.splitledger.settlement.application;

import com.splitledger.payment.port.PaymentProvider;
import com.splitledger.settlement.domain.Settlement;
import com.splitledger.settlement.domain.SettlementStatus;
import com.splitledger.settlement.infra.SettlementRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks providers whether their pending payments have gone through.
 *
 * <p>Lives in {@code settlement} rather than in {@code payment}, because confirming a
 * settlement is a settlement concern and a poller in the payment module would mean the two
 * depend on each other -- which ArchUnit refuses, rightly. Providers answer questions
 * about orders; deciding what that means for a debt happens here.
 *
 * <p>Only polling providers answer at all. A real gateway pushes a webhook and returns
 * nothing here, so once Razorpay lands in S7 this loop will simply find nothing of its to
 * do -- the same code, no special case.
 */
@Component
public class ProviderPaymentPoller {

    private static final Logger log = LoggerFactory.getLogger(ProviderPaymentPoller.class);

    private final SettlementRepository settlements;
    private final SettlementService settlementService;
    private final List<PaymentProvider> providers;

    ProviderPaymentPoller(SettlementRepository settlements, SettlementService settlementService,
            List<PaymentProvider> providers) {
        this.settlements = settlements;
        this.settlementService = settlementService;
        this.providers = providers;
    }

    @Scheduled(cron = "${app.payments.poll-cron}")
    public void pollPendingPayments() {
        for (PaymentProvider provider : providers) {
            if (!provider.method().isGateway()) {
                continue;
            }
            for (Settlement settlement : settlements.findAllByMethodAndStatus(provider.method(),
                    SettlementStatus.PENDING)) {
                confirmIfPaid(provider, settlement);
            }
        }
    }

    private void confirmIfPaid(PaymentProvider provider, Settlement settlement) {
        if (settlement.gatewayOrderId() == null) {
            return;
        }
        try {
            Optional<String> paymentId = provider.completedPaymentId(settlement.gatewayOrderId(),
                    settlement.createdAt());
            if (paymentId.isPresent()) {
                settlementService.confirmFromProvider(settlement.id(), paymentId.get());
            }
        } catch (RuntimeException failure) {
            // One settlement that can no longer be confirmed -- the debt moved under it,
            // say -- must not stop the rest of the sweep.
            log.warn("Could not confirm settlement {}: {}", settlement.id(),
                    failure.getMessage());
        }
    }
}
