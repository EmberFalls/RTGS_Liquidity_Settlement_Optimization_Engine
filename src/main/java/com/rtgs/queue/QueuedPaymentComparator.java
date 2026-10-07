package com.rtgs.queue;

import com.rtgs.payment.PaymentInstruction;
import java.time.Instant;
import java.util.Comparator;

public final class QueuedPaymentComparator implements Comparator<PaymentInstruction> {
    public static final QueuedPaymentComparator INSTANCE = new QueuedPaymentComparator();
    private QueuedPaymentComparator() { }

    @Override public int compare(PaymentInstruction a, PaymentInstruction b) {
        return Comparator.comparingInt((PaymentInstruction p) -> p.priority().ordinal())
                .thenComparing(p -> p.deadline() == null ? Instant.MAX : p.deadline())
                .thenComparing(PaymentInstruction::queuedAt)
                .thenComparing(PaymentInstruction::paymentId)
                .compare(a, b);
    }
}
