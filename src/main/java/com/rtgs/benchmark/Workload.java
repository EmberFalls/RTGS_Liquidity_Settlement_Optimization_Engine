package com.rtgs.benchmark;

import com.rtgs.payment.PaymentInstruction;
import java.util.List;

public record Workload(WorkloadConfig config, List<OpeningParticipant> participants, List<ScheduledPayment> payments) {
    public Workload { participants = List.copyOf(participants); payments = List.copyOf(payments); }
    public record OpeningParticipant(String participantId, long openingLiquidityMinor) { }
    public record ScheduledPayment(long offsetNanos, PaymentInstruction payment) { }
}
