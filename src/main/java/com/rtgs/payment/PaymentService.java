package com.rtgs.payment;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PaymentService {
    private final ParticipantRegistry registry;
    private final Map<String, PaymentInstruction> payments = new ConcurrentHashMap<>();

    public PaymentService(ParticipantRegistry registry) { this.registry = registry; }

    public PaymentInstruction submit(PaymentInstruction payment) {
        if (payment.status() != PaymentStatus.RECEIVED) throw new IllegalArgumentException("new payment must be RECEIVED");
        registry.validate(payment);
        PaymentInstruction prior = payments.putIfAbsent(payment.paymentId(), payment);
        if (prior != null && !sameInstruction(prior, payment))
            throw new IllegalArgumentException("paymentId already used for different instruction");
        return prior == null ? payment : prior;
    }

    public PaymentInstruction require(String paymentId) {
        PaymentInstruction payment = payments.get(paymentId);
        if (payment == null) throw new IllegalArgumentException("unknown payment: " + paymentId);
        return payment;
    }

    public void replace(PaymentInstruction payment) {
        if (!payments.containsKey(payment.paymentId())) throw new IllegalArgumentException("unknown payment");
        payments.put(payment.paymentId(), payment);
    }

    public Collection<PaymentInstruction> all() { return java.util.List.copyOf(payments.values()); }

    private boolean sameInstruction(PaymentInstruction a, PaymentInstruction b) {
        return a.paymentId().equals(b.paymentId()) && a.sourceParticipantId().equals(b.sourceParticipantId())
                && a.destinationParticipantId().equals(b.destinationParticipantId()) && a.amountMinor() == b.amountMinor()
                && a.priority() == b.priority()
                && java.util.Objects.equals(a.deadline(), b.deadline());
    }
}
