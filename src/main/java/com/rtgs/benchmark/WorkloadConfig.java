package com.rtgs.benchmark;

public record WorkloadConfig(String profile, int paymentCount, int targetRatePerSecond, int participantCount,
                             long openingLiquidityMinor, long minimumAmountMinor, long maximumAmountMinor,
                             AmountDistribution amountDistribution, double urgentRate, double highRate,
                             double deadlineRate, double duplicateRate, int burstSize, long seed) {
    public enum AmountDistribution { UNIFORM, BIMODAL }
    public WorkloadConfig {
        if (paymentCount < 1 || targetRatePerSecond < 1 || participantCount < 2 || openingLiquidityMinor < 0
                || minimumAmountMinor <= 0 || maximumAmountMinor < minimumAmountMinor || burstSize < 1)
            throw new IllegalArgumentException("invalid workload bounds");
        if (urgentRate < 0 || highRate < 0 || urgentRate + highRate > 1
                || deadlineRate < 0 || deadlineRate > 1 || duplicateRate < 0 || duplicateRate > 1)
            throw new IllegalArgumentException("invalid probability");
        if (amountDistribution == null) throw new IllegalArgumentException("amount distribution required");
    }

    public static WorkloadConfig profile(String name, int count, long seed) {
        return switch (name) {
            case "normal-day" -> new WorkloadConfig(name, count, 100, 10, 10000, 10, 100, AmountDistribution.UNIFORM, .05, .20, .10, .01, 1, seed);
            case "peak-day" -> new WorkloadConfig(name, count, 500, 20, 10000, 10, 500, AmountDistribution.BIMODAL, .10, .30, .20, .02, 1, seed);
            case "burst-day" -> new WorkloadConfig(name, count, 500, 20, 10000, 10, 500, AmountDistribution.BIMODAL, .10, .30, .20, .02, 50, seed);
            case "liquidity-constrained" -> new WorkloadConfig(name, count, 100, 10, 30, 10, 100, AmountDistribution.UNIFORM, .10, .30, .20, .01, 1, seed);
            case "gridlock-heavy" -> new WorkloadConfig(name, count, 100, 3, 0, 50, 50, AmountDistribution.UNIFORM, .10, .30, .20, 0, 3, seed);
            case "duplicate-heavy" -> new WorkloadConfig(name, count, 100, 10, 10000, 10, 100, AmountDistribution.UNIFORM, .05, .20, .10, .40, 1, seed);
            default -> throw new IllegalArgumentException("unknown profile: " + name);
        };
    }
}
