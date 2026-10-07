package com.rtgs.gridlock;

import com.rtgs.payment.PaymentInstruction;

public record PaymentEdge(String paymentId, String sourceParticipantId,
                          String destinationParticipantId, long amountMinor) {
    public static PaymentEdge from(PaymentInstruction payment) {
        return new PaymentEdge(payment.paymentId(), payment.sourceParticipantId(),
                payment.destinationParticipantId(), payment.amountMinor());
    }
}
