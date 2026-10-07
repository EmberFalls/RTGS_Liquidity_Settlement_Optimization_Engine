package com.rtgs.gridlock;

import com.rtgs.liquidity.LiquidityService;
import com.rtgs.payment.PaymentInstruction;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

public class ProjectedLiquidityCalculator {
    public Map<String, Long> calculate(Collection<PaymentInstruction> batch, LiquidityService liquidity) {
        return calculate(batch, participantId -> liquidity.available(participantId));
    }

    public Map<String, Long> calculate(Collection<PaymentInstruction> batch, java.util.function.ToLongFunction<String> opening) {
        Map<String, Long> projected = new TreeMap<>();
        for (PaymentInstruction payment : batch) {
            projected.putIfAbsent(payment.sourceParticipantId(), opening.applyAsLong(payment.sourceParticipantId()));
            projected.putIfAbsent(payment.destinationParticipantId(), opening.applyAsLong(payment.destinationParticipantId()));
            projected.put(payment.sourceParticipantId(), Math.subtractExact(projected.get(payment.sourceParticipantId()), payment.amountMinor()));
            projected.put(payment.destinationParticipantId(), Math.addExact(projected.get(payment.destinationParticipantId()), payment.amountMinor()));
        }
        return projected;
    }

    public boolean feasible(Map<String, Long> projected) {
        return projected.values().stream().allMatch(balance -> balance >= 0);
    }
}
