package com.rtgs.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadGeneratorTest {
    @Test void sameSeedProducesSameLogicalWorkload() {
        WorkloadGenerator generator = new WorkloadGenerator();
        var config = WorkloadConfig.profile("normal-day", 1000, 42);
        assertEquals(generator.generate(config), generator.generate(config));
        assertNotEquals(generator.generate(config), generator.generate(WorkloadConfig.profile("normal-day", 1000, 43)));
    }
    @Test void profilesRespectBoundsAndSchedules() {
        for (String profile : List.of("normal-day", "peak-day", "burst-day", "liquidity-constrained", "gridlock-heavy", "duplicate-heavy")) {
            Workload workload = new WorkloadGenerator().generate(WorkloadConfig.profile(profile, 1000, 42));
            assertEquals(1000, workload.payments().size());
            long lastOffset = -1;
            for (var attempt : workload.payments()) {
                assertTrue(attempt.offsetNanos() >= lastOffset);
                lastOffset = attempt.offsetNanos();
                assertTrue(attempt.payment().amountMinor() >= workload.config().minimumAmountMinor());
                assertTrue(attempt.payment().amountMinor() <= workload.config().maximumAmountMinor());
                assertNotEquals(attempt.payment().sourceParticipantId(), attempt.payment().destinationParticipantId());
            }
        }
    }
    @Test void duplicateAndBurstBehavior() {
        var duplicated = new WorkloadGenerator().generate(WorkloadConfig.profile("duplicate-heavy", 1000, 42));
        assertTrue(duplicated.payments().stream().map(p -> p.payment().paymentId()).distinct().count() < 800);
        var burst = new WorkloadGenerator().generate(WorkloadConfig.profile("burst-day", 100, 42));
        assertEquals(burst.payments().get(0).offsetNanos(), burst.payments().get(49).offsetNanos());
        assertTrue(burst.payments().get(50).offsetNanos() > burst.payments().get(49).offsetNanos());
    }
}
