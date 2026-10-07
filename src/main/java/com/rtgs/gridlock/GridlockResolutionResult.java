package com.rtgs.gridlock;

import java.util.List;
import java.util.UUID;

public record GridlockResolutionResult(boolean committed, List<String> settledPaymentIds,
                                       List<String> prunedPaymentIds, UUID batchId, String reason) {
    public GridlockResolutionResult {
        settledPaymentIds = List.copyOf(settledPaymentIds);
        prunedPaymentIds = List.copyOf(prunedPaymentIds);
    }
}
