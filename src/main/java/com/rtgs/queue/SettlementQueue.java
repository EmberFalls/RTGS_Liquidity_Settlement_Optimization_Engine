package com.rtgs.queue;

import com.rtgs.payment.PaymentInstruction;
import com.rtgs.payment.PaymentService;
import com.rtgs.payment.PaymentStatus;
import java.util.List;

/** Queue view derived from payment status so it cannot disagree with the payment store. */
public class SettlementQueue {
    private final PaymentService payments;
    public SettlementQueue(PaymentService payments) { this.payments = payments; }

    public List<PaymentInstruction> ordered() {
        return payments.all().stream().filter(p -> p.status() == PaymentStatus.QUEUED)
                .sorted(QueuedPaymentComparator.INSTANCE).toList();
    }

    public int size() { return ordered().size(); }
}
