package com.rtgs.settlement;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SettlementRecord(UUID settlementId, String paymentId, String sourceParticipantId,
                               String destinationParticipantId, long amountMinor, SettlementType settlementType,
                               Instant settledAt, UUID batchId) {
    public SettlementRecord {
        Objects.requireNonNull(settlementId, "settlementId is required");
        if (paymentId == null || paymentId.isBlank()) throw new IllegalArgumentException("paymentId is required");
        if (sourceParticipantId == null || sourceParticipantId.isBlank()) throw new IllegalArgumentException("sourceParticipantId is required");
        if (destinationParticipantId == null || destinationParticipantId.isBlank()) throw new IllegalArgumentException("destinationParticipantId is required");
        if (sourceParticipantId.equals(destinationParticipantId)) throw new IllegalArgumentException("source and destination must differ");
        if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
        Objects.requireNonNull(settlementType, "settlementType is required");
        Objects.requireNonNull(settledAt, "settledAt is required");
        if ((settlementType == SettlementType.GRIDLOCK_BATCH) != (batchId != null))
            throw new IllegalArgumentException("batchId required exactly for gridlock batch");
    }
}
