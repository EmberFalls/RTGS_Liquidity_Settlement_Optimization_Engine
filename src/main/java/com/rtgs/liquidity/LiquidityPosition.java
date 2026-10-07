package com.rtgs.liquidity;

public record LiquidityPosition(String participantId, long availableMinor) {
    public LiquidityPosition {
        if (participantId == null || participantId.isBlank()) throw new IllegalArgumentException("participantId is required");
        if (availableMinor < 0) throw new IllegalArgumentException("liquidity cannot be negative");
    }
}
