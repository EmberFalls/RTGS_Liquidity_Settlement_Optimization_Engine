package com.rtgs.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AlgorithmBenchmarkTest {
    @Test void comparesSameWorkloadAndFindsExactLiquidity() {
        var workload = new WorkloadGenerator().generate(WorkloadConfig.profile("gridlock-heavy", 3, 42));
        AlgorithmBenchmark benchmark = new AlgorithmBenchmark();
        assertEquals(3, benchmark.run(workload, AlgorithmBenchmark.Strategy.FIFO, 0).unresolvedPayments());
        var optimized = benchmark.run(workload, AlgorithmBenchmark.Strategy.PRIORITY_PLUS_GRIDLOCK, 0);
        assertEquals(3, optimized.settledCount());
        assertEquals(150, optimized.settledValueMinor());
        assertEquals(1, optimized.gridlockBatches());
        assertEquals(50, benchmark.minimumOpeningForAll(workload, AlgorithmBenchmark.Strategy.FIFO));
        assertEquals(0, benchmark.minimumOpeningForAll(workload, AlgorithmBenchmark.Strategy.PRIORITY_PLUS_GRIDLOCK));
    }
}
